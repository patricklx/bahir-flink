/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.connectors.mqtt;

//import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;
//import org.apache.flink.streaming.connectors.mqtt.internal.MQTTExceptionListener;
import org.apache.flink.streaming.connectors.mqtt.internal.RunningChecker;
import org.apache.flink.streaming.util.serialization.SimpleStringSchema;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import scala.Array;

import javax.jms.BytesMessage;
import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.Session;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
//import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MQTTSourceTest {

    private static final long CHECKPOINT_ID = 1;
    private final String BROKER_URL = "tcp://localhost:1883";
    private final String TOPIC_NAME = "hm";
    private final String MSG_ID = "msgId";

    //private ActiveMQConnectionFactory connectionFactory;
    private Session session;
    private Connection connection;
    private Destination destination;
    private MessageConsumer consumer;
    private BytesMessage message;

    private MQTTSource<String> mqttSource;
    private SimpleStringSchema deserializationSchema;
    SourceFunction.SourceContext<String> context;

    @Before
    public void before() throws Exception {
        //connectionFactory = mock(ActiveMQConnectionFactory.class);
        //session = mock(Session.class);
        //connection = mock(Connection.class);
        //destination = mock(Destination.class);
        consumer = mock(MessageConsumer.class);
        context = mock(SourceFunction.SourceContext.class);

        message = mock(BytesMessage.class);

        //when(connectionFactory.createConnection()).thenReturn(connection);
        //when(connection.createSession(anyBoolean(), anyInt())).thenReturn(session);
        when(consumer.receive(anyInt())).thenReturn(message);
        when(session.createConsumer(any(Destination.class))).thenReturn(consumer);
        when(context.getCheckpointLock()).thenReturn(new Object());
        when(message.getJMSMessageID()).thenReturn(MSG_ID);

        deserializationSchema = new SimpleStringSchema();
        MQTTSourceConfig<String> config = new MQTTSourceConfig.MQTTSourceConfigBuilder<String>()
            .setBrokerURL(BROKER_URL)
            .setTopicName(TOPIC_NAME)
            .setDeserializationSchema(deserializationSchema)
            .setRunningChecker(new SingleLoopRunChecker())
            .build();
        mqttSource = new MQTTSource<>(config);
        mqttSource.setRuntimeContext(createRuntimeContext());
        mqttSource.open(new Configuration());
    }

    private RuntimeContext createRuntimeContext() {
        StreamingRuntimeContext runtimeContext = mock(StreamingRuntimeContext.class);
        when(runtimeContext.isCheckpointingEnabled()).thenReturn(true);
        return runtimeContext;
    }

    @Test
    public void readFromTopic() throws Exception {
        MQTTSourceConfig<String> config = new MQTTSourceConfig.MQTTSourceConfigBuilder<String>()
            .setBrokerURL(BROKER_URL)
            .setTopicName(TOPIC_NAME)
            .setDeserializationSchema(deserializationSchema)
            .setRunningChecker(new SingleLoopRunChecker())
            .build();
        mqttSource = new MQTTSource<>(config);
        mqttSource.setRuntimeContext(createRuntimeContext());
        mqttSource.open(new Configuration());
        verify(session).createTopic(TOPIC_NAME);
    }

    @Test
    public void parseReceivedMessage() throws Exception {
        final byte[] bytes = deserializationSchema.serialize("msg");
        when(message.getBodyLength()).thenReturn((long) bytes.length);
        when(message.readBytes(any(byte[].class))).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                byte[] inputBytes = (byte[]) invocationOnMock.getArguments()[0];
                Array.copy(bytes, 0, inputBytes, 0, bytes.length);
                return null;
            }
        });

        mqttSource.run(context);

        verify(context).collect("msg");
    }

    @Test
    public void acknowledgeReceivedMessage() throws Exception {
        mqttSource.run(context);
        mqttSource.acknowledgeIDs(CHECKPOINT_ID, Collections.singletonList(MSG_ID));

        verify(message).acknowledge();
    }

    @Test
    public void handleUnknownIds() throws Exception {
        mqttSource.run(context);
        mqttSource.acknowledgeIDs(CHECKPOINT_ID, Collections.singletonList("unknown-id"));

        verify(message, never()).acknowledge();
    }

    @Test
    public void doNotAcknowledgeMessageTwice() throws Exception {
        mqttSource.run(context);
        mqttSource.acknowledgeIDs(CHECKPOINT_ID, Collections.singletonList(MSG_ID));
        mqttSource.acknowledgeIDs(CHECKPOINT_ID, Collections.singletonList(MSG_ID));

        verify(message, times(1)).acknowledge();
    }

    @Test(expected = JMSException.class)
    public void propagateAsyncException() throws Exception {
        //MQTTExceptionListener exceptionListener = mock(MQTTExceptionListener.class);
        //mqttSource.setExceptionListener(exceptionListener);
        //doThrow(JMSException.class).when(exceptionListener).checkErroneous();
        mqttSource.run(context);
    }

    @Test(expected = RuntimeException.class)
    public void throwAcknowledgeExceptionByDefault() throws Exception {
        doThrow(JMSException.class).when(message).acknowledge();

        mqttSource.run(context);
        mqttSource.acknowledgeIDs(CHECKPOINT_ID, Collections.singletonList(MSG_ID));
    }

    @Test
    public void doNotThrowAcknowledgeExceptionByDefault() throws Exception {
        mqttSource.setLogFailuresOnly(true);

        doThrow(JMSException.class).when(message).acknowledge();

        mqttSource.run(context);
        mqttSource.acknowledgeIDs(CHECKPOINT_ID, Collections.singletonList(MSG_ID));
    }

    @Test
    public void closeResources() throws Exception {
        mqttSource.close();

        verify(consumer).close();
        verify(session).close();
        verify(connection).close();
    }

    @Test
    public void consumerCloseExceptionShouldBePased() throws Exception {
        doThrow(new JMSException("consumer")).when(consumer).close();
        doThrow(new JMSException("session")).when(session).close();
        doThrow(new JMSException("connection")).when(connection).close();

        try {
            mqttSource.close();
            fail("Should throw an exception");
        } catch (RuntimeException ex) {
            assertEquals("consumer", ex.getCause().getMessage());
        }
    }

    @Test
    public void sessionCloseExceptionShouldBePased() throws Exception {
        doThrow(new JMSException("session")).when(session).close();
        doThrow(new JMSException("connection")).when(connection).close();

        try {
            mqttSource.close();
            fail("Should throw an exception");
        } catch (RuntimeException ex) {
            assertEquals("session", ex.getCause().getMessage());
        }
    }

    @Test
    public void connectionCloseExceptionShouldBePased() throws Exception {
        doThrow(new JMSException("connection")).when(connection).close();

        try {
            mqttSource.close();
            fail("Should throw an exception");
        } catch (RuntimeException ex) {
            assertEquals("connection", ex.getCause().getMessage());
        }
    }

    @Test
    public void exceptionsShouldNotBePassedIfLogFailuresOnly() throws Exception {
        doThrow(new JMSException("consumer")).when(consumer).close();
        doThrow(new JMSException("session")).when(session).close();
        doThrow(new JMSException("connection")).when(connection).close();

        mqttSource.setLogFailuresOnly(true);
        mqttSource.close();
    }

    class SingleLoopRunChecker extends RunningChecker {

        int count = 0;

        @Override
        public boolean isRunning() {
            return (count++ == 0);
        }

        @Override
        public void setIsRunning(boolean isRunning) {

        }
    }
}
