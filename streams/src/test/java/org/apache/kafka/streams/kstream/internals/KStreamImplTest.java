/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.kstream.internals;

import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.KeyValueTimestamp;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.TopologyWrapper;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.GlobalKTable;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.JoinWindows;
import org.apache.kafka.streams.kstream.Joined;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.KeyValueMapper;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.Predicate;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.StreamJoined;
import org.apache.kafka.streams.kstream.Transformer;
import org.apache.kafka.streams.kstream.TransformerSupplier;
import org.apache.kafka.streams.kstream.ValueJoiner;
import org.apache.kafka.streams.kstream.ValueMapper;
import org.apache.kafka.streams.kstream.ValueMapperWithKey;
import org.apache.kafka.streams.kstream.ValueTransformer;
import org.apache.kafka.streams.kstream.ValueTransformerSupplier;
import org.apache.kafka.streams.kstream.ValueTransformerWithKey;
import org.apache.kafka.streams.kstream.ValueTransformerWithKeySupplier;
import org.apache.kafka.streams.processor.FailOnInvalidTimestamp;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.TopicNameExtractor;
import org.apache.kafka.streams.processor.internals.ProcessorTopology;
import org.apache.kafka.streams.processor.internals.SourceNode;
import org.apache.kafka.test.MockMapper;
import org.apache.kafka.test.MockProcessor;
import org.apache.kafka.test.MockProcessorSupplier;
import org.apache.kafka.test.MockValueJoiner;
import org.apache.kafka.test.StreamsTestUtils;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.time.Duration.ofMillis;
import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class KStreamImplTest {

    private final Consumed<String, String> stringConsumed = Consumed.with(Serdes.String(), Serdes.String());
    private final MockProcessorSupplier<String, String> processorSupplier = new MockProcessorSupplier<>();
    private final TransformerSupplier<String, String, KeyValue<String, String>> transformerSupplier =
        () -> new Transformer<String, String, KeyValue<String, String>>() {
            @Override
            public void init(final ProcessorContext context) {}

            @Override
            public KeyValue<String, String> transform(final String key, final String value) {
                return new KeyValue<>(key, value);
            }

            @Override
            public void close() {}
        };
    private final TransformerSupplier<String, String, Iterable<KeyValue<String, String>>> flatTransformerSupplier =
        () -> new Transformer<String, String, Iterable<KeyValue<String, String>>>() {
            @Override
            public void init(final ProcessorContext context) {}

            @Override
            public Iterable<KeyValue<String, String>> transform(final String key, final String value) {
                return Collections.singleton(new KeyValue<>(key, value));
            }

            @Override
            public void close() {}
        };
    private final ValueTransformerSupplier<String, String> valueTransformerSupplier =
        () -> new ValueTransformer<String, String>() {
            @Override
            public void init(final ProcessorContext context) {}

            @Override
            public String transform(final String value) {
                return value;
            }

            @Override
            public void close() {}
        };
    private final ValueTransformerWithKeySupplier<String, String, String> valueTransformerWithKeySupplier =
        () -> new ValueTransformerWithKey<String, String, String>() {
            @Override
            public void init(final ProcessorContext context) {}

            @Override
            public String transform(final String key, final String value) {
                return value;
            }

            @Override
            public void close() {}
        };
    private final ValueTransformerSupplier<String, Iterable<String>> flatValueTransformerSupplier =
        () -> new ValueTransformer<String, Iterable<String>>() {
            @Override
            public void init(final ProcessorContext context) {}

            @Override
            public Iterable<String> transform(final String value) {
                return Collections.singleton(value);
            }

            @Override
            public void close() {}
        };
    private final ValueTransformerWithKeySupplier<String, String, Iterable<String>> flatValueTransformerWithKeySupplier =
        () -> new ValueTransformerWithKey<String, String, Iterable<String>>() {
            @Override
            public void init(final ProcessorContext context) {}

            @Override
            public Iterable<String> transform(final String key, final String value) {
                return Collections.singleton(value);
            }

            @Override
            public void close() {}
        };

    private KStream<String, String> testStream;
    private StreamsBuilder builder;

    private final Properties props = StreamsTestUtils.getStreamsConfig(Serdes.String(), Serdes.String());

    private final Serde<String> mySerde = new Serdes.StringSerde();

    @Before
    public void before() {
        builder = new StreamsBuilder();
        testStream = builder.stream("source");
    }

    @Test
    public void shouldNotAllowNullPredicateOnFilter() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.filter(null));
        assertThat(exception.getMessage(), equalTo("predicate can't be null"));
    }

    @Test
    public void shouldNotAllowNullPredicateOnFilterWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.filter(null, Named.as("filter")));
        assertThat(exception.getMessage(), equalTo("predicate can't be null"));
    }

    @Test
    public void shouldNotAllowNullNamedOnFilter() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.filter((k, v) -> true, null));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullPredicateOnFilterNot() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.filterNot(null));
        assertThat(exception.getMessage(), equalTo("predicate can't be null"));
    }

    @Test
    public void shouldNotAllowNullPredicateOnFilterNotWithName() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.filterNot(null, Named.as("filter")));
        assertThat(exception.getMessage(), equalTo("predicate can't be null"));
    }

    @Test
    public void shouldNotAllowNullNamedOnFilterNot() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.filterNot((k, v) -> true, null));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullMapperOnSelectKey() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.selectKey(null));
        assertThat(exception.getMessage(), equalTo("mapper can't be null"));
    }

    @Test
    public void shouldNotAllowNullMapperOnSelectKeyWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.selectKey(null, Named.as("keySelector")));
        assertThat(exception.getMessage(), equalTo("mapper can't be null"));
    }

    @Test
    public void shouldNotAllowNullNamedOnSelectKey() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.selectKey((k, v) -> k, null));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullMapperOnMap() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.map(null));
        assertThat(exception.getMessage(), equalTo("mapper can't be null"));
    }

    @Test
    public void shouldNotAllowNullMapperOnMapWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.map(null, Named.as("map")));
        assertThat(exception.getMessage(), equalTo("mapper can't be null"));
    }

    @Test
    public void shouldNotAllowNullNamedOnMap() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.map(KeyValue::pair, null));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullMapperOnMapValues() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.mapValues((ValueMapper<Object, Object>) null));
        assertThat(exception.getMessage(), equalTo("valueMapper can't be null"));
    }

    @Test
    public void shouldNotAllowNullMapperOnMapValuesWithKey() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.mapValues((ValueMapperWithKey<Object, Object, Object>) null));
        assertThat(exception.getMessage(), equalTo("valueMapperWithKey can't be null"));
    }

    @Test
    public void shouldNotAllowNullMapperOnMapValuesWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.mapValues((ValueMapper<Object, Object>) null, Named.as("valueMapper")));
        assertThat(exception.getMessage(), equalTo("valueMapper can't be null"));
    }

    @Test
    public void shouldNotAllowNullMapperOnMapValuesWithKeyWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.mapValues(
                (ValueMapperWithKey<Object, Object, Object>) null,
                Named.as("valueMapperWithKey")));
        assertThat(exception.getMessage(), equalTo("valueMapperWithKey can't be null"));
    }

    @Test
    public void shouldNotAllowNullNamedOnMapValues() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.mapValues(v -> v, null));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullNamedOnMapValuesWithKey() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.mapValues((k, v) -> v, null));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullMapperOnFlatMap() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatMap(null));
        assertThat(exception.getMessage(), equalTo("mapper can't be null"));
    }

    @Test
    public void shouldNotAllowNullMapperOnFlatMapWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatMap(null, Named.as("flatMapper")));
        assertThat(exception.getMessage(), equalTo("mapper can't be null"));
    }

    @Test
    public void shouldNotAllowNullNamedOnFlatMap() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatMap((k, v) -> Collections.emptyList(), null));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullMapperOnFlatMapValues() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatMapValues((ValueMapper<Object, Iterable<Object>>) null));
        assertThat(exception.getMessage(), equalTo("valueMapper can't be null"));
    }

    @Test
    public void shouldNotAllowNullMapperOnFlatMapValuesWithKey() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatMapValues((ValueMapperWithKey<Object, Object, ? extends Iterable<Object>>) null));
        assertThat(exception.getMessage(), equalTo("valueMapper can't be null"));
    }

    @Test
    public void shouldNotAllowNullMapperOnFlatMapValuesWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatMapValues(
                (ValueMapper<Object, Iterable<Object>>) null,
                Named.as("flatValueMapper")));
        assertThat(exception.getMessage(), equalTo("valueMapper can't be null"));
    }

    @Test
    public void shouldNotAllowNullMapperOnFlatMapValuesWithKeyWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatMapValues(
                (ValueMapperWithKey<Object, Object, ? extends Iterable<Object>>) null,
                Named.as("flatValueMapperWithKey")));
        assertThat(exception.getMessage(), equalTo("valueMapper can't be null"));
    }

    @Test
    public void shouldNotAllowNullNameOnFlatMapValues() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatMapValues(v -> Collections.emptyList(), null));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullNameOnFlatMapValuesWithKey() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatMapValues((k, v) -> Collections.emptyList(), null));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullPrintedOnPrint() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.print(null));
        assertThat(exception.getMessage(), equalTo("printed can't be null"));
    }

    @Test
    public void shouldNotAllowNullActionOnForEach() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.foreach(null));
        assertThat(exception.getMessage(), equalTo("action can't be null"));
    }

    @Test
    public void shouldNotAllowNullActionOnForEachWithName() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.foreach(null, Named.as("foreach")));
        assertThat(exception.getMessage(), equalTo("action can't be null"));
    }

    @Test
    public void shouldNotAllowNullNamedOnForEach() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.foreach((k, v) -> { }, null));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullActionOnPeek() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.peek(null));
        assertThat(exception.getMessage(), equalTo("action can't be null"));
    }

    @Test
    public void shouldNotAllowNullActionOnPeekWithName() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.peek(null, Named.as("peek")));
        assertThat(exception.getMessage(), equalTo("action can't be null"));
    }

    @Test
    public void shouldNotAllowNullNamedOnPeek() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.peek((k, v) -> { }, null));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void shouldNotAllowNullPredicatedOnBranch() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.branch((Predicate[]) null));
        assertThat(exception.getMessage(), equalTo("predicates can't be a null array"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldHaveAtLeastOnPredicateWhenBranching() {
        final IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> testStream.branch());
        assertThat(exception.getMessage(), equalTo("branch() requires at least one predicate"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveAtLeastOnPredicateWhenBranchingWithNamed() {
        final IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> testStream.branch(Named.as("branch")));
        assertThat(exception.getMessage(), equalTo("branch() requires at least one predicate"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldNotAllowNullNamedOnBranch() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.branch((Named) null, (k, v) -> true));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldCantHaveNullPredicate() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.branch((Predicate<Object, Object>) null));
        assertThat(exception.getMessage(), equalTo("predicates can't be null"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldCantHaveNullPredicateWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.branch(Named.as("branch"), (Predicate<Object, Object>) null));
        assertThat(exception.getMessage(), equalTo("predicates can't be null"));
    }

    @Test
    public void shouldNotAllowNullKStreamOnMerge() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.merge(null));
        assertThat(exception.getMessage(), equalTo("stream can't be null"));
    }

    @Test
    public void shouldNotAllowNullKStreamOnMergeWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.merge(null, Named.as("merge")));
        assertThat(exception.getMessage(), equalTo("stream can't be null"));
    }

    @Test
    public void shouldNotAllowNullNamedOnMerge() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.merge(testStream, null));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullTopicOnThrough() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.through(null));
        assertThat(exception.getMessage(), equalTo("topic can't be null"));
    }

    @Test
    public void shouldNotAllowNullTopicOnThroughWithProduced() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.through(null, Produced.as("through")));
        assertThat(exception.getMessage(), equalTo("topic can't be null"));
    }

    @Test
    public void shouldNotAllowNullProducedOnThrough() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.through("topic", null));
        assertThat(exception.getMessage(), equalTo("produced can't be null"));
    }

    @Test
    public void shouldNotAllowNullTopicOnTo() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.to((String) null));
        assertThat(exception.getMessage(), equalTo("topic can't be null"));
    }

    @Test
    public void shouldNotAllowNullTopicChooserOnTo() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.to((TopicNameExtractor<String, String>) null));
        assertThat(exception.getMessage(), equalTo("topicExtractor can't be null"));
    }

    @Test
    public void shouldNotAllowNullTopicOnToWithProduced() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.to((String) null, Produced.as("to")));
        assertThat(exception.getMessage(), equalTo("topic can't be null"));
    }

    @Test
    public void shouldNotAllowNullTopicChooserOnToWithProduced() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.to((TopicNameExtractor<String, String>) null, Produced.as("to")));
        assertThat(exception.getMessage(), equalTo("topicExtractor can't be null"));
    }

    @Test
    public void shouldNotAllowNullProducedOnToWithTopicName() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.to("topic", null));
        assertThat(exception.getMessage(), equalTo("produced can't be null"));
    }

    @Test
    public void shouldNotAllowNullProducedOnToWithTopicChooser() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.to((k, v, ctx) -> "topic", null));
        assertThat(exception.getMessage(), equalTo("produced can't be null"));
    }

    @Test(expected = NullPointerException.class)
    public void shouldNotAllowNullOtherStreamOnJoin() {
        testStream.join(null, MockValueJoiner.TOSTRING_JOINER, JoinWindows.of(ofMillis(10)));
    }

    @Test(expected = NullPointerException.class)
    public void shouldNotAllowNullValueJoinerOnJoin() {
        testStream.join(testStream, null, JoinWindows.of(ofMillis(10)));
    }

    @Test(expected = NullPointerException.class)
    public void shouldNotAllowNullJoinWindowsOnJoin() {
        testStream.join(testStream, MockValueJoiner.TOSTRING_JOINER, null);
    }

    @Test(expected = NullPointerException.class)
    public void shouldNotAllowNullTableOnTableJoin() {
        testStream.leftJoin(null, MockValueJoiner.TOSTRING_JOINER);
    }

    @Test(expected = NullPointerException.class)
    public void shouldNotAllowNullValueMapperOnTableJoin() {
        testStream.leftJoin(builder.table("topic", stringConsumed), null);
    }

    @Test(expected = NullPointerException.class)
    public void shouldNotAllowNullSelectorOnGroupBy() {
        testStream.groupBy(null);
    }

    @Test(expected = NullPointerException.class)
    public void shouldNotAllowNullTableOnJoinWithGlobalTable() {
        testStream.join(null,
                        MockMapper.selectValueMapper(),
                        MockValueJoiner.TOSTRING_JOINER);
    }

    @Test(expected = NullPointerException.class)
    public void shouldNotAllowNullMapperOnJoinWithGlobalTable() {
        testStream.join(builder.globalTable("global", stringConsumed),
                        null,
                        MockValueJoiner.TOSTRING_JOINER);
    }

    @Test(expected = NullPointerException.class)
    public void shouldNotAllowNullJoinerOnJoinWithGlobalTable() {
        testStream.join(builder.globalTable("global", stringConsumed),
                        MockMapper.selectValueMapper(),
                        null);
    }

    @Test(expected = NullPointerException.class)
    public void shouldNotAllowNullTableOnJLeftJoinWithGlobalTable() {
        testStream.leftJoin(null,
                            MockMapper.selectValueMapper(),
                            MockValueJoiner.TOSTRING_JOINER);
    }

    @Test(expected = NullPointerException.class)
    public void shouldNotAllowNullMapperOnLeftJoinWithGlobalTable() {
        testStream.leftJoin(builder.globalTable("global", stringConsumed),
                        null,
                        MockValueJoiner.TOSTRING_JOINER);
    }

    @Test(expected = NullPointerException.class)
    public void shouldNotAllowNullJoinerOnLeftJoinWithGlobalTable() {
        testStream.leftJoin(builder.globalTable("global", stringConsumed),
                        MockMapper.selectValueMapper(),
                        null);
    }

    @Test
    public void shouldThrowNullPointerOnLeftJoinWithTableWhenJoinedIsNull() {
        final KTable<String, String> table = builder.table("blah", stringConsumed);
        try {
            testStream.leftJoin(table,
                                MockValueJoiner.TOSTRING_JOINER,
                                null);
            fail("Should have thrown NullPointerException");
        } catch (final NullPointerException e) {
            // ok
        }
    }

    @Test
    public void shouldThrowNullPointerOnJoinWithTableWhenJoinedIsNull() {
        final KTable<String, String> table = builder.table("blah", stringConsumed);
        try {
            testStream.join(table,
                            MockValueJoiner.TOSTRING_JOINER,
                            null);
            fail("Should have thrown NullPointerException");
        } catch (final NullPointerException e) {
            // ok
        }
    }

    @Test(expected = NullPointerException.class)
    public void shouldThrowNullPointerOnJoinWithStreamWhenStreamJoinedIsNull() {
        testStream.join(
            testStream,
            MockValueJoiner.TOSTRING_JOINER,
            JoinWindows.of(ofMillis(10)),
            (StreamJoined<String, String, String>) null);
    }

    @Test(expected = NullPointerException.class)
    public void shouldThrowNullPointerOnOuterJoinStreamJoinedIsNull() {
        testStream.outerJoin(
            testStream,
            MockValueJoiner.TOSTRING_JOINER,
            JoinWindows.of(ofMillis(10)),
            (StreamJoined<String, String, String>) null);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testNumProcesses() {
        final StreamsBuilder builder = new StreamsBuilder();

        final KStream<String, String> source1 = builder.stream(Arrays.asList("topic-1", "topic-2"), stringConsumed);

        final KStream<String, String> source2 = builder.stream(Arrays.asList("topic-3", "topic-4"), stringConsumed);

        final KStream<String, String> stream1 = source1.filter((key, value) -> true)
            .filterNot((key, value) -> false);

        final KStream<String, Integer> stream2 = stream1.mapValues(Integer::new);

        final KStream<String, Integer> stream3 = source2.flatMapValues((ValueMapper<String, Iterable<Integer>>)
            value -> Collections.singletonList(Integer.valueOf(value)));

        final KStream<String, Integer>[] streams2 = stream2.branch(
            (key, value) -> (value % 2) == 0,
            (key, value) -> true
        );

        final KStream<String, Integer>[] streams3 = stream3.branch(
            (key, value) -> (value % 2) == 0,
            (key, value) -> true
        );

        final int anyWindowSize = 1;
        final StreamJoined<String, Integer, Integer> joined = StreamJoined.with(Serdes.String(), Serdes.Integer(), Serdes.Integer());
        final KStream<String, Integer> stream4 = streams2[0].join(streams3[0],
            Integer::sum, JoinWindows.of(ofMillis(anyWindowSize)), joined);

        streams2[1].join(streams3[1], Integer::sum,
            JoinWindows.of(ofMillis(anyWindowSize)), joined);

        stream4.to("topic-5");

        streams2[1].through("topic-6").process(new MockProcessorSupplier<>());

        assertEquals(2 + // sources
                2 + // stream1
                1 + // stream2
                1 + // stream3
                1 + 2 + // streams2
                1 + 2 + // streams3
                5 * 2 + // stream2-stream3 joins
                1 + // to
                2 + // through
                1, // process
            TopologyWrapper.getInternalTopologyBuilder(builder.build()).setApplicationId("X").build(null).processors().size());
    }

    @SuppressWarnings("rawtypes")
    @Test
    public void shouldPreserveSerdesForOperators() {
        final StreamsBuilder builder = new StreamsBuilder();
        final KStream<String, String> stream1 = builder.stream(Collections.singleton("topic-1"), stringConsumed);
        final KTable<String, String> table1 = builder.table("topic-2", stringConsumed);
        final GlobalKTable<String, String> table2 = builder.globalTable("topic-2", stringConsumed);
        final ConsumedInternal<String, String> consumedInternal = new ConsumedInternal<>(stringConsumed);

        final KeyValueMapper<String, String, String> selector = (key, value) -> key;
        final KeyValueMapper<String, String, Iterable<KeyValue<String, String>>> flatSelector = (key, value) -> Collections.singleton(new KeyValue<>(key, value));
        final ValueMapper<String, String> mapper = value -> value;
        final ValueMapper<String, Iterable<String>> flatMapper = Collections::singleton;
        final ValueJoiner<String, String, String> joiner = (value1, value2) -> value1;

        assertEquals(((AbstractStream) stream1.filter((key, value) -> false)).keySerde(), consumedInternal.keySerde());
        assertEquals(((AbstractStream) stream1.filter((key, value) -> false)).valueSerde(), consumedInternal.valueSerde());

        assertEquals(((AbstractStream) stream1.filterNot((key, value) -> false)).keySerde(), consumedInternal.keySerde());
        assertEquals(((AbstractStream) stream1.filterNot((key, value) -> false)).valueSerde(), consumedInternal.valueSerde());

        assertNull(((AbstractStream) stream1.selectKey(selector)).keySerde());
        assertEquals(((AbstractStream) stream1.selectKey(selector)).valueSerde(), consumedInternal.valueSerde());

        assertNull(((AbstractStream) stream1.map(KeyValue::new)).keySerde());
        assertNull(((AbstractStream) stream1.map(KeyValue::new)).valueSerde());

        assertEquals(((AbstractStream) stream1.mapValues(mapper)).keySerde(), consumedInternal.keySerde());
        assertNull(((AbstractStream) stream1.mapValues(mapper)).valueSerde());

        assertNull(((AbstractStream) stream1.flatMap(flatSelector)).keySerde());
        assertNull(((AbstractStream) stream1.flatMap(flatSelector)).valueSerde());

        assertEquals(((AbstractStream) stream1.flatMapValues(flatMapper)).keySerde(), consumedInternal.keySerde());
        assertNull(((AbstractStream) stream1.flatMapValues(flatMapper)).valueSerde());

        assertNull(((AbstractStream) stream1.transform(transformerSupplier)).keySerde());
        assertNull(((AbstractStream) stream1.transform(transformerSupplier)).valueSerde());

        assertEquals(((AbstractStream) stream1.transformValues(valueTransformerSupplier)).keySerde(), consumedInternal.keySerde());
        assertNull(((AbstractStream) stream1.transformValues(valueTransformerSupplier)).valueSerde());

        assertNull(((AbstractStream) stream1.merge(stream1)).keySerde());
        assertNull(((AbstractStream) stream1.merge(stream1)).valueSerde());

        assertEquals(((AbstractStream) stream1.through("topic-3")).keySerde(), consumedInternal.keySerde());
        assertEquals(((AbstractStream) stream1.through("topic-3")).valueSerde(), consumedInternal.valueSerde());
        assertEquals(((AbstractStream) stream1.through("topic-3", Produced.with(mySerde, mySerde))).keySerde(), mySerde);
        assertEquals(((AbstractStream) stream1.through("topic-3", Produced.with(mySerde, mySerde))).valueSerde(), mySerde);

        assertEquals(((AbstractStream) stream1.groupByKey()).keySerde(), consumedInternal.keySerde());
        assertEquals(((AbstractStream) stream1.groupByKey()).valueSerde(), consumedInternal.valueSerde());
        assertEquals(((AbstractStream) stream1.groupByKey(Grouped.with(mySerde, mySerde))).keySerde(), mySerde);
        assertEquals(((AbstractStream) stream1.groupByKey(Grouped.with(mySerde, mySerde))).valueSerde(), mySerde);

        assertNull(((AbstractStream) stream1.groupBy(selector)).keySerde());
        assertEquals(((AbstractStream) stream1.groupBy(selector)).valueSerde(), consumedInternal.valueSerde());
        assertEquals(((AbstractStream) stream1.groupBy(selector, Grouped.with(mySerde, mySerde))).keySerde(), mySerde);
        assertEquals(((AbstractStream) stream1.groupBy(selector, Grouped.with(mySerde, mySerde))).valueSerde(), mySerde);

        assertNull(((AbstractStream) stream1.join(stream1, joiner, JoinWindows.of(Duration.ofMillis(100L)))).keySerde());
        assertNull(((AbstractStream) stream1.join(stream1, joiner, JoinWindows.of(Duration.ofMillis(100L)))).valueSerde());
        assertEquals(((AbstractStream) stream1.join(stream1, joiner, JoinWindows.of(Duration.ofMillis(100L)), StreamJoined.with(mySerde, mySerde, mySerde))).keySerde(), mySerde);
        assertNull(((AbstractStream) stream1.join(stream1, joiner, JoinWindows.of(Duration.ofMillis(100L)), StreamJoined.with(mySerde, mySerde, mySerde))).valueSerde());

        assertNull(((AbstractStream) stream1.leftJoin(stream1, joiner, JoinWindows.of(Duration.ofMillis(100L)))).keySerde());
        assertNull(((AbstractStream) stream1.leftJoin(stream1, joiner, JoinWindows.of(Duration.ofMillis(100L)))).valueSerde());
        assertEquals(((AbstractStream) stream1.leftJoin(stream1, joiner, JoinWindows.of(Duration.ofMillis(100L)), StreamJoined.with(mySerde, mySerde, mySerde))).keySerde(), mySerde);
        assertNull(((AbstractStream) stream1.leftJoin(stream1, joiner, JoinWindows.of(Duration.ofMillis(100L)), StreamJoined.with(mySerde, mySerde, mySerde))).valueSerde());

        assertNull(((AbstractStream) stream1.outerJoin(stream1, joiner, JoinWindows.of(Duration.ofMillis(100L)))).keySerde());
        assertNull(((AbstractStream) stream1.outerJoin(stream1, joiner, JoinWindows.of(Duration.ofMillis(100L)))).valueSerde());
        assertEquals(((AbstractStream) stream1.outerJoin(stream1, joiner, JoinWindows.of(Duration.ofMillis(100L)), StreamJoined.with(mySerde, mySerde, mySerde))).keySerde(), mySerde);
        assertNull(((AbstractStream) stream1.outerJoin(stream1, joiner, JoinWindows.of(Duration.ofMillis(100L)), StreamJoined.with(mySerde, mySerde, mySerde))).valueSerde());

        assertEquals(((AbstractStream) stream1.join(table1, joiner)).keySerde(), consumedInternal.keySerde());
        assertNull(((AbstractStream) stream1.join(table1, joiner)).valueSerde());
        assertEquals(((AbstractStream) stream1.join(table1, joiner, Joined.with(mySerde, mySerde, mySerde))).keySerde(), mySerde);
        assertNull(((AbstractStream) stream1.join(table1, joiner, Joined.with(mySerde, mySerde, mySerde))).valueSerde());

        assertEquals(((AbstractStream) stream1.leftJoin(table1, joiner)).keySerde(), consumedInternal.keySerde());
        assertNull(((AbstractStream) stream1.leftJoin(table1, joiner)).valueSerde());
        assertEquals(((AbstractStream) stream1.leftJoin(table1, joiner, Joined.with(mySerde, mySerde, mySerde))).keySerde(), mySerde);
        assertNull(((AbstractStream) stream1.leftJoin(table1, joiner, Joined.with(mySerde, mySerde, mySerde))).valueSerde());

        assertEquals(((AbstractStream) stream1.join(table2, selector, joiner)).keySerde(), consumedInternal.keySerde());
        assertNull(((AbstractStream) stream1.join(table2, selector, joiner)).valueSerde());

        assertEquals(((AbstractStream) stream1.leftJoin(table2, selector, joiner)).keySerde(), consumedInternal.keySerde());
        assertNull(((AbstractStream) stream1.leftJoin(table2, selector, joiner)).valueSerde());
    }

    @Test
    public void shouldUseRecordMetadataTimestampExtractorWithThrough() {
        final StreamsBuilder builder = new StreamsBuilder();
        final KStream<String, String> stream1 = builder.stream(Arrays.asList("topic-1", "topic-2"), stringConsumed);
        final KStream<String, String> stream2 = builder.stream(Arrays.asList("topic-3", "topic-4"), stringConsumed);

        stream1.to("topic-5");
        stream2.through("topic-6");

        final ProcessorTopology processorTopology = TopologyWrapper.getInternalTopologyBuilder(builder.build()).setApplicationId("X").build(null);
        assertThat(processorTopology.source("topic-6").getTimestampExtractor(), instanceOf(FailOnInvalidTimestamp.class));
        assertNull(processorTopology.source("topic-4").getTimestampExtractor());
        assertNull(processorTopology.source("topic-3").getTimestampExtractor());
        assertNull(processorTopology.source("topic-2").getTimestampExtractor());
        assertNull(processorTopology.source("topic-1").getTimestampExtractor());
    }

    @Test
    public void shouldSendDataThroughTopicUsingProduced() {
        final StreamsBuilder builder = new StreamsBuilder();
        final String input = "topic";
        final KStream<String, String> stream = builder.stream(input, stringConsumed);
        stream.through("through-topic", Produced.with(Serdes.String(), Serdes.String())).process(processorSupplier);

        try (final TopologyTestDriver driver = new TopologyTestDriver(builder.build(), props)) {
            final TestInputTopic<String, String> inputTopic =
                driver.createInputTopic(input, new StringSerializer(), new StringSerializer(), Instant.ofEpochMilli(0L), Duration.ZERO);
            inputTopic.pipeInput("a", "b");
        }
        assertThat(processorSupplier.theCapturedProcessor().processed, equalTo(Collections.singletonList(new KeyValueTimestamp<>("a", "b", 0))));
    }

    @Test
    public void shouldSendDataToTopicUsingProduced() {
        final StreamsBuilder builder = new StreamsBuilder();
        final String input = "topic";
        final KStream<String, String> stream = builder.stream(input, stringConsumed);
        stream.to("to-topic", Produced.with(Serdes.String(), Serdes.String()));
        builder.stream("to-topic", stringConsumed).process(processorSupplier);

        try (final TopologyTestDriver driver = new TopologyTestDriver(builder.build(), props)) {
            final TestInputTopic<String, String> inputTopic =
                driver.createInputTopic(input, new StringSerializer(), new StringSerializer(), Instant.ofEpochMilli(0L), Duration.ZERO);
            inputTopic.pipeInput("e", "f");
        }
        assertThat(processorSupplier.theCapturedProcessor().processed, equalTo(Collections.singletonList(new KeyValueTimestamp<>("e", "f", 0))));
    }

    @Test
    public void shouldSendDataToDynamicTopics() {
        final StreamsBuilder builder = new StreamsBuilder();
        final String input = "topic";
        final KStream<String, String> stream = builder.stream(input, stringConsumed);
        stream.to((key, value, context) -> context.topic() + "-" + key + "-" + value.substring(0, 1),
            Produced.with(Serdes.String(), Serdes.String()));
        builder.stream(input + "-a-v", stringConsumed).process(processorSupplier);
        builder.stream(input + "-b-v", stringConsumed).process(processorSupplier);

        try (final TopologyTestDriver driver = new TopologyTestDriver(builder.build(), props)) {
            final TestInputTopic<String, String> inputTopic =
                driver.createInputTopic(input, new StringSerializer(), new StringSerializer(), Instant.ofEpochMilli(0L), Duration.ZERO);
            inputTopic.pipeInput("a", "v1");
            inputTopic.pipeInput("a", "v2");
            inputTopic.pipeInput("b", "v1");
        }
        final List<MockProcessor<String, String>> mockProcessors = processorSupplier.capturedProcessors(2);
        assertThat(mockProcessors.get(0).processed, equalTo(asList(new KeyValueTimestamp<>("a", "v1", 0),
            new KeyValueTimestamp<>("a", "v2", 0))));
        assertThat(mockProcessors.get(1).processed, equalTo(Collections.singletonList(new KeyValueTimestamp<>("b", "v1", 0))));
    }

    @SuppressWarnings({"rawtypes", "deprecation"}) // specifically testing the deprecated variant
    @Test
    public void shouldUseRecordMetadataTimestampExtractorWhenInternalRepartitioningTopicCreatedWithRetention() {
        final StreamsBuilder builder = new StreamsBuilder();
        final KStream<String, String> kStream = builder.stream("topic-1", stringConsumed);
        final ValueJoiner<String, String, String> valueJoiner = MockValueJoiner.instance(":");
        final long windowSize = TimeUnit.MILLISECONDS.convert(1, TimeUnit.DAYS);
        final KStream<String, String> stream = kStream
            .map((key, value) -> KeyValue.pair(value, value));
        stream.join(kStream,
            valueJoiner,
            JoinWindows.of(ofMillis(windowSize)).grace(ofMillis(3 * windowSize)),
            Joined.with(Serdes.String(),
                Serdes.String(),
                Serdes.String()))
            .to("output-topic", Produced.with(Serdes.String(), Serdes.String()));

        final ProcessorTopology topology = TopologyWrapper.getInternalTopologyBuilder(builder.build()).setApplicationId("X").build();

        final SourceNode originalSourceNode = topology.source("topic-1");

        for (final SourceNode sourceNode : topology.sources()) {
            if (sourceNode.name().equals(originalSourceNode.name())) {
                assertNull(sourceNode.getTimestampExtractor());
            } else {
                assertThat(sourceNode.getTimestampExtractor(), instanceOf(FailOnInvalidTimestamp.class));
            }
        }
    }

    @SuppressWarnings("rawtypes")
    @Test
    public void shouldUseRecordMetadataTimestampExtractorWhenInternalRepartitioningTopicCreated() {
        final StreamsBuilder builder = new StreamsBuilder();
        final KStream<String, String> kStream = builder.stream("topic-1", stringConsumed);
        final ValueJoiner<String, String, String> valueJoiner = MockValueJoiner.instance(":");
        final long windowSize = TimeUnit.MILLISECONDS.convert(1, TimeUnit.DAYS);
        final KStream<String, String> stream = kStream
            .map((key, value) -> KeyValue.pair(value, value));
        stream.join(
            kStream,
            valueJoiner,
            JoinWindows.of(ofMillis(windowSize)).grace(ofMillis(3L * windowSize)),
            StreamJoined.with(Serdes.String(), Serdes.String(), Serdes.String())
        )
            .to("output-topic", Produced.with(Serdes.String(), Serdes.String()));

        final ProcessorTopology topology = TopologyWrapper.getInternalTopologyBuilder(builder.build()).setApplicationId("X").build();

        final SourceNode originalSourceNode = topology.source("topic-1");

        for (final SourceNode sourceNode : topology.sources()) {
            if (sourceNode.name().equals(originalSourceNode.name())) {
                assertNull(sourceNode.getTimestampExtractor());
            } else {
                assertThat(sourceNode.getTimestampExtractor(), instanceOf(FailOnInvalidTimestamp.class));
            }
        }
    }

    @Test
    public void shouldPropagateRepartitionFlagAfterGlobalKTableJoin() {
        final StreamsBuilder builder = new StreamsBuilder();
        final GlobalKTable<String, String> globalKTable = builder.globalTable("globalTopic");
        final KeyValueMapper<String, String, String> kvMappper = (k, v) -> k + v;
        final ValueJoiner<String, String, String> valueJoiner = (v1, v2) -> v1 + v2;
        builder.<String, String>stream("topic").selectKey((k, v) -> v)
            .join(globalKTable, kvMappper, valueJoiner)
            .groupByKey()
            .count();

        final Pattern repartitionTopicPattern = Pattern.compile("Sink: .*-repartition");
        final String topology = builder.build().describe().toString();
        final Matcher matcher = repartitionTopicPattern.matcher(topology);
        assertTrue(matcher.find());
        final String match = matcher.group();
        assertThat(match, notNullValue());
        assertTrue(match.endsWith("repartition"));
    }

    @Test
    public void shouldMergeTwoStreams() {
        final String topic1 = "topic-1";
        final String topic2 = "topic-2";

        final KStream<String, String> source1 = builder.stream(topic1);
        final KStream<String, String> source2 = builder.stream(topic2);
        final KStream<String, String> merged = source1.merge(source2);

        merged.process(processorSupplier);

        try (final TopologyTestDriver driver = new TopologyTestDriver(builder.build(), props)) {
            final TestInputTopic<String, String> inputTopic1 =
                driver.createInputTopic(topic1, new StringSerializer(), new StringSerializer(), Instant.ofEpochMilli(0L), Duration.ZERO);
            final TestInputTopic<String, String> inputTopic2 =
                driver.createInputTopic(topic2, new StringSerializer(), new StringSerializer(), Instant.ofEpochMilli(0L), Duration.ZERO);
            inputTopic1.pipeInput("A", "aa");
            inputTopic2.pipeInput("B", "bb");
            inputTopic2.pipeInput("C", "cc");
            inputTopic1.pipeInput("D", "dd");
        }

        assertEquals(asList(new KeyValueTimestamp<>("A", "aa", 0),
            new KeyValueTimestamp<>("B", "bb", 0),
            new KeyValueTimestamp<>("C", "cc", 0),
            new KeyValueTimestamp<>("D", "dd", 0)), processorSupplier.theCapturedProcessor().processed);
    }

    @Test
    public void shouldMergeMultipleStreams() {
        final String topic1 = "topic-1";
        final String topic2 = "topic-2";
        final String topic3 = "topic-3";
        final String topic4 = "topic-4";

        final KStream<String, String> source1 = builder.stream(topic1);
        final KStream<String, String> source2 = builder.stream(topic2);
        final KStream<String, String> source3 = builder.stream(topic3);
        final KStream<String, String> source4 = builder.stream(topic4);
        final KStream<String, String> merged = source1.merge(source2).merge(source3).merge(source4);

        merged.process(processorSupplier);

        try (final TopologyTestDriver driver = new TopologyTestDriver(builder.build(), props)) {
            final TestInputTopic<String, String> inputTopic1 =
                driver.createInputTopic(topic1, new StringSerializer(), new StringSerializer(), Instant.ofEpochMilli(0L), Duration.ZERO);
            final TestInputTopic<String, String> inputTopic2 =
                driver.createInputTopic(topic2, new StringSerializer(), new StringSerializer(), Instant.ofEpochMilli(0L), Duration.ZERO);
            final TestInputTopic<String, String> inputTopic3 =
                driver.createInputTopic(topic3, new StringSerializer(), new StringSerializer(), Instant.ofEpochMilli(0L), Duration.ZERO);
            final TestInputTopic<String, String> inputTopic4 =
                driver.createInputTopic(topic4, new StringSerializer(), new StringSerializer(), Instant.ofEpochMilli(0L), Duration.ZERO);

            inputTopic1.pipeInput("A", "aa", 1L);
            inputTopic2.pipeInput("B", "bb", 9L);
            inputTopic3.pipeInput("C", "cc", 2L);
            inputTopic4.pipeInput("D", "dd", 8L);
            inputTopic4.pipeInput("E", "ee", 3L);
            inputTopic3.pipeInput("F", "ff", 7L);
            inputTopic2.pipeInput("G", "gg", 4L);
            inputTopic1.pipeInput("H", "hh", 6L);
        }

        assertEquals(asList(new KeyValueTimestamp<>("A", "aa", 1),
            new KeyValueTimestamp<>("B", "bb", 9),
            new KeyValueTimestamp<>("C", "cc", 2),
            new KeyValueTimestamp<>("D", "dd", 8),
            new KeyValueTimestamp<>("E", "ee", 3),
            new KeyValueTimestamp<>("F", "ff", 7),
            new KeyValueTimestamp<>("G", "gg", 4),
            new KeyValueTimestamp<>("H", "hh", 6)),
            processorSupplier.theCapturedProcessor().processed);
    }

    @Test
    public void shouldProcessFromSourceThatMatchPattern() {
        final KStream<String, String> pattern2Source = builder.stream(Pattern.compile("topic-\\d"));

        pattern2Source.process(processorSupplier);

        try (final TopologyTestDriver driver = new TopologyTestDriver(builder.build(), props)) {
            final TestInputTopic<String, String> inputTopic3 =
                driver.createInputTopic("topic-3", new StringSerializer(), new StringSerializer(), Instant.ofEpochMilli(0L), Duration.ZERO);
            final TestInputTopic<String, String> inputTopic4 =
                driver.createInputTopic("topic-4", new StringSerializer(), new StringSerializer(), Instant.ofEpochMilli(0L), Duration.ZERO);
            final TestInputTopic<String, String> inputTopic5 =
                driver.createInputTopic("topic-5", new StringSerializer(), new StringSerializer(), Instant.ofEpochMilli(0L), Duration.ZERO);
            final TestInputTopic<String, String> inputTopic6 =
                driver.createInputTopic("topic-6", new StringSerializer(), new StringSerializer(), Instant.ofEpochMilli(0L), Duration.ZERO);
            final TestInputTopic<String, String> inputTopic7 =
                driver.createInputTopic("topic-7", new StringSerializer(), new StringSerializer(), Instant.ofEpochMilli(0L), Duration.ZERO);

            inputTopic3.pipeInput("A", "aa", 1L);
            inputTopic4.pipeInput("B", "bb", 5L);
            inputTopic5.pipeInput("C", "cc", 10L);
            inputTopic6.pipeInput("D", "dd", 8L);
            inputTopic7.pipeInput("E", "ee", 3L);
        }

        assertEquals(asList(new KeyValueTimestamp<>("A", "aa", 1),
            new KeyValueTimestamp<>("B", "bb", 5),
            new KeyValueTimestamp<>("C", "cc", 10),
            new KeyValueTimestamp<>("D", "dd", 8),
            new KeyValueTimestamp<>("E", "ee", 3)),
            processorSupplier.theCapturedProcessor().processed);
    }

    @Test
    public void shouldProcessFromSourcesThatMatchMultiplePattern() {
        final String topic3 = "topic-without-pattern";

        final KStream<String, String> pattern2Source1 = builder.stream(Pattern.compile("topic-\\d"));
        final KStream<String, String> pattern2Source2 = builder.stream(Pattern.compile("topic-[A-Z]"));
        final KStream<String, String> source3 = builder.stream(topic3);
        final KStream<String, String> merged = pattern2Source1.merge(pattern2Source2).merge(source3);

        merged.process(processorSupplier);

        try (final TopologyTestDriver driver = new TopologyTestDriver(builder.build(), props)) {
            final TestInputTopic<String, String> inputTopic3 =
                driver.createInputTopic("topic-3", new StringSerializer(), new StringSerializer(), Instant.ofEpochMilli(0L), Duration.ZERO);
            final TestInputTopic<String, String> inputTopic4 =
                driver.createInputTopic("topic-4", new StringSerializer(), new StringSerializer(), Instant.ofEpochMilli(0L), Duration.ZERO);
            final TestInputTopic<String, String> inputTopicA =
                driver.createInputTopic("topic-A", new StringSerializer(), new StringSerializer(), Instant.ofEpochMilli(0L), Duration.ZERO);
            final TestInputTopic<String, String> inputTopicZ =
                driver.createInputTopic("topic-Z", new StringSerializer(), new StringSerializer(), Instant.ofEpochMilli(0L), Duration.ZERO);
            final TestInputTopic<String, String> inputTopic =
                driver.createInputTopic(topic3, new StringSerializer(), new StringSerializer(), Instant.ofEpochMilli(0L), Duration.ZERO);

            inputTopic3.pipeInput("A", "aa", 1L);
            inputTopic4.pipeInput("B", "bb", 5L);
            inputTopicA.pipeInput("C", "cc", 10L);
            inputTopicZ.pipeInput("D", "dd", 8L);
            inputTopic.pipeInput("E", "ee", 3L);
        }

        assertEquals(asList(new KeyValueTimestamp<>("A", "aa", 1),
            new KeyValueTimestamp<>("B", "bb", 5),
            new KeyValueTimestamp<>("C", "cc", 10),
            new KeyValueTimestamp<>("D", "dd", 8),
            new KeyValueTimestamp<>("E", "ee", 3)),
            processorSupplier.theCapturedProcessor().processed);
    }

    @Test
    public void shouldNotAllowNullTransformerSupplierOnTransform() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transform(null));
        assertThat(exception.getMessage(), equalTo("transformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullTransformerSupplierOnTransformWithStores() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transform(null, "storeName"));
        assertThat(exception.getMessage(), equalTo("transformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullTransformerSupplierOnTransformWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transform(null, Named.as("transformer")));
        assertThat(exception.getMessage(), equalTo("transformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullTransformerSupplierOnTransformWithNamedAndStores() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transform(null, Named.as("transformer"), "storeName"));
        assertThat(exception.getMessage(), equalTo("transformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullStoreNamesOnTransform() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transform(transformerSupplier, (String[]) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't be a null array"));
    }

    @Test
    public void shouldNotAllowNullStoreNameOnTransform() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transform(transformerSupplier, (String) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't contain `null` as store name"));
    }

    @Test
    public void shouldNotAllowNullStoreNamesOnTransformWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transform(transformerSupplier, Named.as("transform"), (String[]) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't be a null array"));
    }

    @Test
    public void shouldNotAllowNullStoreNameOnTransformWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transform(transformerSupplier, Named.as("transform"), (String) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't contain `null` as store name"));
    }

    @Test
    public void shouldNotAllowNullNamedOnTransform() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transform(transformerSupplier, (Named) null));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullNamedOnTransformWithStoreName() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transform(transformerSupplier, (Named) null, "storeName"));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullTransformerSupplierOnFlatTransform() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransform(null));
        assertThat(exception.getMessage(), equalTo("transformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullTransformerSupplierOnFlatTransformWithStores() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransform(null, "storeName"));
        assertThat(exception.getMessage(), equalTo("transformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullTransformerSupplierOnFlatTransformWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransform(null, Named.as("flatTransformer")));
        assertThat(exception.getMessage(), equalTo("transformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullTransformerSupplierOnFlatTransformWithNamedAndStores() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransform(null, Named.as("flatTransformer"), "storeName"));
        assertThat(exception.getMessage(), equalTo("transformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullStoreNamesOnFlatTransform() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransform(flatTransformerSupplier, (String[]) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't be a null array"));
    }

    @Test
    public void shouldNotAllowNullStoreNameOnFlatTransform() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransform(flatTransformerSupplier, (String) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't contain `null` as store name"));
    }

    @Test
    public void shouldNotAllowNullStoreNamesOnFlatTransformWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransform(flatTransformerSupplier, Named.as("flatTransform"), (String[]) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't be a null array"));
    }

    @Test
    public void shouldNotAllowNullStoreNameOnFlatTransformWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransform(flatTransformerSupplier, Named.as("flatTransform"), (String) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't contain `null` as store name"));
    }

    @Test
    public void shouldNotAllowNullNamedOnFlatTransform() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransform(flatTransformerSupplier, (Named) null));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullNamedOnFlatTransformWithStoreName() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransform(flatTransformerSupplier, (Named) null, "storeName"));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullValueTransformerSupplierOnTransformValues() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transformValues((ValueTransformerSupplier<Object, Object>) null));
        assertThat(exception.getMessage(), equalTo("valueTransformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullValueTransformerWithKeySupplierOnTransformValues() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transformValues((ValueTransformerWithKeySupplier<Object, Object, Object>) null));
        assertThat(exception.getMessage(), equalTo("valueTransformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullValueTransformerSupplierOnTransformValuesWithStores() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transformValues(
                (ValueTransformerSupplier<Object, Object>) null,
                "storeName"));
        assertThat(exception.getMessage(), equalTo("valueTransformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullValueTransformerWithKeySupplierOnTransformValuesWithStores() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transformValues(
                (ValueTransformerWithKeySupplier<Object, Object, Object>) null,
                "storeName"));
        assertThat(exception.getMessage(), equalTo("valueTransformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullValueTransformerSupplierOnTransformValuesWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transformValues(
                (ValueTransformerSupplier<Object, Object>) null,
                Named.as("valueTransformer")));
        assertThat(exception.getMessage(), equalTo("valueTransformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullValueTransformerWithKeySupplierOnTransformValuesWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transformValues(
                (ValueTransformerWithKeySupplier<Object, Object, Object>) null,
                Named.as("valueTransformerWithKey")));
        assertThat(exception.getMessage(), equalTo("valueTransformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullValueTransformerSupplierOnTransformValuesWithNamedAndStores() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transformValues(
                (ValueTransformerSupplier<Object, Object>) null,
                Named.as("valueTransformer"),
                "storeName"));
        assertThat(exception.getMessage(), equalTo("valueTransformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullValueTransformerWithKeySupplierOnTransformValuesWithNamedAndStores() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transformValues(
                (ValueTransformerWithKeySupplier<Object, Object, Object>) null,
                Named.as("valueTransformerWithKey"),
                "storeName"));
        assertThat(exception.getMessage(), equalTo("valueTransformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullStoreNamesOnTransformValuesWithValueTransformerSupplier() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transformValues(
                valueTransformerSupplier,
                (String[]) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't be a null array"));
    }

    @Test
    public void shouldNotAllowNullStoreNamesOnTransformValuesWithValueTransformerWithKeySupplier() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transformValues(
                valueTransformerWithKeySupplier,
                (String[]) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't be a null array"));
    }

    @Test
    public void shouldNotAllowNullStoreNameOnTransformValuesWithValueTransformerSupplier() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transformValues(
                valueTransformerSupplier, (String) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't contain `null` as store name"));
    }

    @Test
    public void shouldNotAllowNullStoreNameOnTransformValuesWithValueTransformerWithKeySupplier() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transformValues(
                valueTransformerWithKeySupplier,
                (String) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't contain `null` as store name"));
    }

    @Test
    public void shouldNotAllowNullStoreNamesOnTransformValuesWithValueTransformerSupplierWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transformValues(
                valueTransformerSupplier,
                Named.as("valueTransformer"),
                (String[]) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't be a null array"));
    }

    @Test
    public void shouldNotAllowNullStoreNamesOnTransformValuesWithValueTransformerWithKeySupplierWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transformValues(
                valueTransformerWithKeySupplier,
                Named.as("valueTransformer"),
                (String[]) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't be a null array"));
    }

    @Test
    public void shouldNotAllowNullStoreNameOnTransformValuesWithValueTransformerSupplierWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transformValues(
                valueTransformerSupplier,
                Named.as("valueTransformer"),
                (String) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't contain `null` as store name"));
    }

    @Test
    public void shouldNotAllowNullStoreNameOnTransformValuesWithValueTransformerWithKeySupplierWithName() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transformValues(
                valueTransformerWithKeySupplier,
                Named.as("valueTransformerWithKey"),
                (String) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't contain `null` as store name"));
    }

    @Test
    public void shouldNotAllowNullNamedOnTransformValuesWithValueTransformerSupplier() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transformValues(
                valueTransformerSupplier,
                (Named) null));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullNamedOnTransformValuesWithValueTransformerWithKeySupplier() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transformValues(
                valueTransformerWithKeySupplier,
                (Named) null));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullNamedOnTransformValuesWithValueTransformerSupplierAndStores() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transformValues(
                valueTransformerSupplier,
                (Named) null,
                "storeName"));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullNamedOnTransformValuesWithValueTransformerWithKeySupplierAndStores() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.transformValues(
                valueTransformerWithKeySupplier,
                (Named) null,
                "storeName"));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullValueTransformerSupplierOnFlatTransformValues() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransformValues((ValueTransformerSupplier<Object, Iterable<Object>>) null));
        assertThat(exception.getMessage(), equalTo("valueTransformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullValueTransformerWithKeySupplierOnFlatTransformValues() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransformValues((ValueTransformerWithKeySupplier<Object, Object, Iterable<Object>>) null));
        assertThat(exception.getMessage(), equalTo("valueTransformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullValueTransformerSupplierOnFlatTransformValuesWithStores() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransformValues(
                (ValueTransformerSupplier<Object, Iterable<Object>>) null,
                "stateStore"));
        assertThat(exception.getMessage(), equalTo("valueTransformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullValueTransformerWithKeySupplierOnFlatTransformValuesWithStores() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransformValues(
                (ValueTransformerWithKeySupplier<Object, Object, Iterable<Object>>) null,
                "stateStore"));
        assertThat(exception.getMessage(), equalTo("valueTransformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullValueTransformerSupplierOnFlatTransformValuesWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransformValues(
                (ValueTransformerSupplier<Object, Iterable<Object>>) null,
                Named.as("flatValueTransformer")));
        assertThat(exception.getMessage(), equalTo("valueTransformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullValueTransformerWithKeySupplierOnFlatTransformValuesWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransformValues(
                (ValueTransformerWithKeySupplier<Object, Object, Iterable<Object>>) null,
                Named.as("flatValueWithKeyTransformer")));
        assertThat(exception.getMessage(), equalTo("valueTransformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullValueTransformerSupplierOnFlatTransformValuesWithNamedAndStores() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransformValues(
                (ValueTransformerSupplier<Object, Iterable<Object>>) null,
                Named.as("flatValueTransformer"),
                "stateStore"));
        assertThat(exception.getMessage(), equalTo("valueTransformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullValueTransformerWithKeySupplierOnFlatTransformValuesWithNamedAndStores() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransformValues(
                (ValueTransformerWithKeySupplier<Object, Object, Iterable<Object>>) null,
                Named.as("flatValueWitKeyTransformer"),
                "stateStore"));
        assertThat(exception.getMessage(), equalTo("valueTransformerSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullStoreNamesOnFlatTransformValuesWithFlatValueSupplier() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransformValues(
                flatValueTransformerSupplier,
                (String[]) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't be a null array"));
    }

    @Test
    public void shouldNotAllowNullStoreNamesOnFlatTransformValuesWithFlatValueWithKeySupplier() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransformValues(
                flatValueTransformerWithKeySupplier,
                (String[]) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't be a null array"));
    }

    @Test
    public void shouldNotAllowNullStoreNameOnFlatTransformValuesWithFlatValueSupplier() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransformValues(
                flatValueTransformerSupplier,
                (String) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't contain `null` as store name"));
    }

    @Test
    public void shouldNotAllowNullStoreNameOnFlatTransformValuesWithFlatValueWithKeySupplier() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransformValues(
                flatValueTransformerWithKeySupplier,
                (String) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't contain `null` as store name"));
    }

    @Test
    public void shouldNotAllowNullStoreNamesOnFlatTransformValuesWithFlatValueSupplierAndNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransformValues(
                flatValueTransformerSupplier,
                Named.as("flatValueTransformer"),
                (String[]) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't be a null array"));
    }

    @Test
    public void shouldNotAllowNullStoreNamesOnFlatTransformValuesWithFlatValueWithKeySupplierAndNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransformValues(
                flatValueTransformerWithKeySupplier,
                Named.as("flatValueWitKeyTransformer"),
                (String[]) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't be a null array"));
    }

    @Test
    public void shouldNotAllowNullStoreNameOnFlatTransformValuesWithFlatValueSupplierAndNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransformValues(
                flatValueTransformerSupplier,
                Named.as("flatValueTransformer"),
                (String) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't contain `null` as store name"));
    }

    @Test
    public void shouldNotAllowNullStoreNameOnFlatTransformValuesWithFlatValueWithKeySupplierAndNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransformValues(
                flatValueTransformerWithKeySupplier,
                Named.as("flatValueWitKeyTransformer"),
                (String) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't contain `null` as store name"));
    }

    @Test
    public void shouldNotAllowNullNamedOnFlatTransformValuesWithFlatValueSupplier() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransformValues(
                flatValueTransformerSupplier,
                (Named) null));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullNamedOnFlatTransformValuesWithFlatValueWithKeySupplier() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransformValues(
                flatValueTransformerWithKeySupplier,
                (Named) null));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullNamedOnFlatTransformValuesWithFlatValueSupplierAndStores() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransformValues(
                flatValueTransformerSupplier,
                (Named) null,
                "storeName"));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullNamedOnFlatTransformValuesWithFlatValueWithKeySupplierAndStore() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.flatTransformValues(
                flatValueTransformerWithKeySupplier,
                (Named) null,
                "storeName"));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullProcessSupplierOnProcess() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.process(null));
        assertThat(exception.getMessage(), equalTo("processorSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullProcessSupplierOnProcessWithStores() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.process(null, "storeName"));
        assertThat(exception.getMessage(), equalTo("processorSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullProcessSupplierOnProcessWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.process(null, Named.as("processor")));
        assertThat(exception.getMessage(), equalTo("processorSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullProcessSupplierOnProcessWithNamedAndStores() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.process(null, Named.as("processor"), "stateStore"));
        assertThat(exception.getMessage(), equalTo("processorSupplier can't be null"));
    }

    @Test
    public void shouldNotAllowNullStoreNamesOnProcess() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.process(processorSupplier, (String[]) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't be a null array"));
    }

    @Test
    public void shouldNotAllowNullStoreNameOnProcess() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.process(processorSupplier, (String) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't be null"));
    }

    @Test
    public void shouldNotAllowNullStoreNamesOnProcessWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.process(processorSupplier, Named.as("processor"), (String[]) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't be a null array"));
    }

    @Test
    public void shouldNotAllowNullStoreNameOnProcessWithNamed() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.process(processorSupplier, Named.as("processor"), (String) null));
        assertThat(exception.getMessage(), equalTo("stateStoreNames can't be null"));
    }

    @Test
    public void shouldNotAllowNullNamedOnProcess() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.process(processorSupplier, (Named) null));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

    @Test
    public void shouldNotAllowNullNamedOnProcessWithStores() {
        final NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testStream.process(processorSupplier, (Named) null, "storeName"));
        assertThat(exception.getMessage(), equalTo("named can't be null"));
    }

}
