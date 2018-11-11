/*
 * Copyright 2015-2018 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.Test;
import zipkin2.Endpoint;
import zipkin2.Span;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

public class NodeTest {
  List<String> messages = new ArrayList<>();

  Logger logger = new Logger("", null) {
    {
      setLevel(Level.ALL);
    }

    @Override public void log(Level level, String msg) {
      assertThat(level).isEqualTo(Level.FINE);
      messages.add(msg);
    }
  };

  @Test(expected = NullPointerException.class)
  public void addValue_nullNotAllowed() {
    new Node<>(null, null).setValue(null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void addChild_selfNotAllowed() {
    Node<Character> a = new Node<>(null, 'a');
    a.addChild(a);
  }

  /**
   * <p>The following tree should traverse in alphabetical order <pre>{@code
   *
   *          a
   *        / | \
   *       b  c  d
   *      /|\     \
   *     e f g     h
   * }</pre>
   */
  @Test public void traversesBreadthFirst() {
    Node<Character> a = new Node<>(null, 'a');
    Node<Character> b = new Node<>(null, 'b');
    Node<Character> c = new Node<>(null, 'c');
    Node<Character> d = new Node<>(null, 'd');
    // root(a) has children b, c, d
    a.addChild(b).addChild(c).addChild(d);
    Node<Character> e = new Node<>(null, 'e');
    Node<Character> f = new Node<>(null, 'f');
    Node<Character> g = new Node<>(null, 'g');
    // child(b) has children e, f, g
    b.addChild(e).addChild(f).addChild(g);
    Node<Character> h = new Node<>(null, 'h');
    // f has no children
    // child(g) has child h
    g.addChild(h);

    assertThat(a.traverse()).extracting(Node::value)
      .containsExactly('a', 'b', 'c', 'd', 'e', 'f', 'g', 'h');
  }

  /**
   * Makes sure that the trace tree is constructed based on parent-child, not by parameter order.
   */
  @Test public void constructsTraceTree() {
    List<Span> trace = asList(
      Span.newBuilder().traceId("a").id("a").build(),
      Span.newBuilder().traceId("a").parentId("a").id("b").build(),
      Span.newBuilder().traceId("a").parentId("b").id("c").build(),
      Span.newBuilder().traceId("a").parentId("c").id("d").build()
    );
    assertAncestry(trace);
  }

  /** Same as {@link #constructsTraceTree()}, except with shared span ID */
  @Test public void constructsTraceTree_sharedId() {
    List<Span> trace = asList(
      Span.newBuilder().traceId("a").id("a").build(),
      Span.newBuilder().traceId("a").parentId("a").id("b").build(),
      Span.newBuilder().traceId("a").parentId("a").id("b").shared(true).build(),
      Span.newBuilder().traceId("a").parentId("b").id("c").build()
    );
    assertAncestry(trace);
  }

  @Test public void constructsTraceTree_sharedRootId() {
    List<Span> trace = asList(
      Span.newBuilder().traceId("a").id("a").build(),
      Span.newBuilder().traceId("a").id("a").shared(true).build(),
      Span.newBuilder().traceId("a").parentId("a").id("b").build(),
      Span.newBuilder().traceId("a").parentId("b").id("c").build()
    );
    assertAncestry(trace);
  }

  void assertAncestry(List<Span> trace) {
    Node<Span> root = buildTree(trace);
    assertThat(root.value()).isEqualTo(trace.get(0));

    Node<Span> current = root;
    for (int i = 1, length = trace.size() - 1; i < length; i++) {
      current = current.children.get(0);
      assertThat(current.value).isEqualTo(trace.get(i));
      assertThat(current.children).extracting(Node::value)
        .containsExactly(trace.get(i + 1));
    }
  }

  @Test public void constructsTraceTree_qualifiesChildrenOfDuplicateServerSpans() {
    List<Span> trace = asList(
      Span.newBuilder().traceId("a").id("a").build(),
      Span.newBuilder().traceId("a").parentId("a").id("b").build(),
      localServiceName("foo", Span.newBuilder().traceId("a").parentId("a").id("b").shared(true)),
      localServiceName("bar", Span.newBuilder().traceId("a").parentId("a").id("b").shared(true)),
      localServiceName("bar", Span.newBuilder().traceId("a").parentId("b").id("c")),
      localServiceName("foo", Span.newBuilder().traceId("a").parentId("b").id("d"))
    );

    Node<Span> a = buildTree(trace);
    assertThat(a.value()).isEqualTo(trace.get(0));

    Node<Span> b_client = a.children().get(0);
    assertThat(b_client.value()).isEqualTo(trace.get(1));
    assertThat(b_client.children()).extracting(Node::value)
      .containsExactly(trace.get(3), trace.get(2));

    Node<Span> b_server_bar = b_client.children().get(0);
    assertThat(b_server_bar.qualifier).isEqualTo("bar");
    assertThat(b_server_bar.children()).extracting(Node::value)
      .containsExactly(trace.get(4));

    Node<Span> b_server_foo = b_client.children().get(1);
    assertThat(b_server_foo.qualifier).isEqualTo("foo");
    assertThat(b_server_foo.children()).extracting(Node::value)
      .containsExactly(trace.get(5));
  }

  static Span localServiceName(String serviceName, Span.Builder builder) {
    return builder.localEndpoint(Endpoint.newBuilder().serviceName(serviceName).build()).build();
  }

  Node<Span> buildTree(List<Span> trace) {
    // TRACE is sorted with root span first, lets reverse them to make
    // sure the trace is stitched together by id.
    List<Span> copy = new ArrayList<>(trace);
    Collections.reverse(copy);

    Node.TreeBuilder<Span> treeBuilder =
      new Node.TreeBuilder<>(logger, copy.get(0).traceId());
    for (Span span : copy) {
      treeBuilder.addNode(span.localServiceName(), span.parentId(), span.id(), span.shared(), span);
    }
    return treeBuilder.build();
  }

  @Test public void constructsTraceTree_dedupes() {
    List<Span> trace = asList(
      Span.newBuilder().traceId("a").id("a").build(),
      Span.newBuilder().traceId("a").id("a").build(),
      Span.newBuilder().traceId("a").id("a").build()
    );

    Node.TreeBuilder<Span> treeBuilder =
      new Node.TreeBuilder<>(logger, trace.get(0).traceId());
    for (Span span : trace) {
      treeBuilder.addNode(span.localServiceName(), span.parentId(), span.id(), span.shared(), span);
    }
    Node<Span> root = treeBuilder.build();

    assertThat(root.value())
      .isEqualTo(trace.get(0));
    assertThat(root.children())
      .isEmpty();
  }

  @Test public void constructTree_noChildLeftBehind() {
    List<Span> spans = asList(
      Span.newBuilder().traceId("a").id("b").name("root-0").build(),
      Span.newBuilder().traceId("a").parentId("b").id("c").name("child-0").build(),
      Span.newBuilder().traceId("a").parentId("b").id("d").name("child-1").build(),
      Span.newBuilder().traceId("a").id("e").name("lost-0").build(),
      Span.newBuilder().traceId("a").id("f").name("lost-1").build());
    int treeSize = 0;
    Node.TreeBuilder<Span> treeBuilder =
      new Node.TreeBuilder<>(logger, spans.get(0).traceId());
    for (Span span : spans) {
      assertThat(
        treeBuilder.addNode(span.localServiceName(), span.parentId(), span.id(), span.shared(),
          span)
      ).isTrue();
    }
    Node<Span> tree = treeBuilder.build();
    Iterator<Node<Span>> iter = tree.traverse();
    while (iter.hasNext()) {
      iter.next();
      treeSize++;
    }
    assertThat(treeSize).isEqualTo(spans.size());
    assertThat(messages).containsExactly(
      "attributing span missing parent to root: traceId=000000000000000a, rootSpanId=000000000000000b, spanId=000000000000000e",
      "attributing span missing parent to root: traceId=000000000000000a, rootSpanId=000000000000000b, spanId=000000000000000f"
    );
  }

  @Test public void constructTree_headless() {
    Span s2 = Span.newBuilder().traceId("a").parentId("a").id("b").name("s2").build();
    Span s3 = Span.newBuilder().traceId("a").parentId("a").id("c").name("s3").build();
    Span s4 = Span.newBuilder().traceId("a").parentId("a").id("d").name("s4").build();

    Node.TreeBuilder<Span> treeBuilder = new Node.TreeBuilder<>(logger, s2.traceId());
    for (Span span : asList(s2, s3, s4)) {
      treeBuilder.addNode(span.localServiceName(), span.parentId(), span.id(), span.shared(), span);
    }
    Node<Span> root = treeBuilder.build();
    assertThat(root.value())
      .isNull();
    assertThat(root.children()).extracting(Node::value)
      .containsExactly(s2, s3, s4);
    assertThat(messages).containsExactly(
      "substituting dummy node for missing root span: traceId=000000000000000a"
    );
  }

  @Test public void constructTree_outOfOrder() {
    Span s2 = Span.newBuilder().traceId("a").parentId("a").id("b").name("s2").build();
    Span s3 = Span.newBuilder().traceId("a").parentId("a").id("c").name("s3").build();
    Span s4 = Span.newBuilder().traceId("a").parentId("a").id("d").name("s4").build();

    Node.TreeBuilder<Span> treeBuilder = new Node.TreeBuilder<>(logger, s2.traceId());
    for (Span span : asList(s2, s3, s4)) {
      treeBuilder.addNode(span.localServiceName(), span.parentId(), span.id(), span.shared(), span);
    }
    Node<Span> root = treeBuilder.build();
    assertThat(root.value())
      .isNull();
    assertThat(root.children()).extracting(Node::value)
      .containsExactly(s2, s3, s4);
    assertThat(messages).containsExactly(
      "substituting dummy node for missing root span: traceId=000000000000000a"
    );
  }

  @Test public void addNode_skipsOnCycle() {
    Span s1 = Span.newBuilder().traceId("a").parentId(null).id("a").name("s1").build();
    Span s2 = Span.newBuilder().traceId("a").parentId("b").id("b").name("s2").build();

    Node.TreeBuilder<Span> treeBuilder = new Node.TreeBuilder<>(logger, s2.traceId());
    treeBuilder.addNode(s1.localServiceName(), s1.parentId(), s1.id(), s1.shared(), s1);
    assertThat(
      treeBuilder.addNode(s2.localServiceName(), s2.parentId(), s2.id(), s2.shared(), s2)
    ).isFalse();

    treeBuilder.build();
    assertThat(messages).containsExactly(
      "skipping circular dependency: traceId=000000000000000a, spanId=000000000000000b"
    );
  }
}
