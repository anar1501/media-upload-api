package com.company.videouploadapi.config;

import lombok.Getter;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListenerConfigurer;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistrar;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Getter
@Configuration
public class RabbitMQConfig implements RabbitListenerConfigurer {
    public static final String VIDEO_PROCESSING_EXCHANGE = "video-processing";
    public static final String QUEUE_480P = "video-480p-queue";
    public static final String QUEUE_720P = "video-720p-queue";
    public static final String ROUTING_KEY_480P = "480p-process";
    public static final String ROUTING_KEY_720P = "720p-process";
    public static final String DLX_EXCHANGE = "video-dlx";
    public static final String DLX_QUEUE = "video-dlx-queue";

    @Bean
    public TopicExchange videoProcessingExchange() {
        return ExchangeBuilder.topicExchange(VIDEO_PROCESSING_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue video480pQueue() {
        return QueueBuilder.durable(QUEUE_480P).withArgument("x-dead-letter-exchange", DLX_EXCHANGE) // Set the DLX
                .build();
    }

    @Bean
    public Queue video720pQueue() {
        return QueueBuilder.durable(QUEUE_720P).withArgument("x-dead-letter-exchange", DLX_EXCHANGE) // Set the DLX
                .build();
    }

    @Bean
    public Binding binding480p(Queue video480pQueue, TopicExchange videoProcessingExchange) {
        return BindingBuilder.bind(video480pQueue).to(videoProcessingExchange).with(ROUTING_KEY_480P);
    }

    @Bean
    public Binding binding720p(Queue video720pQueue, TopicExchange videoProcessingExchange) {
        return BindingBuilder.bind(video720pQueue).to(videoProcessingExchange).with(ROUTING_KEY_720P);
    }

    @Bean
    public TopicExchange deadLetterExchange() {
        return ExchangeBuilder.topicExchange(DLX_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return new Queue(DLX_QUEUE, true);
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with("#"); // Bind all messages to DLX
    }

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }


    @Override
    public void configureRabbitListeners(RabbitListenerEndpointRegistrar registrar) {
        registrar.setContainerFactory(simpleRabbitListenerContainerFactory());
    }

    @Bean
    public SimpleRabbitListenerContainerFactory simpleRabbitListenerContainerFactory() {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory()); // Define your connection factory
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(1); // Set to 1 to ensure only one consumer instance
        factory.setMaxConcurrentConsumers(1); // Set to 1 to process one message at a time
        factory.setPrefetchCount(1); // Set prefetch count to 1
        return factory;
    }

    @Bean
    public ConnectionFactory connectionFactory() {
        CachingConnectionFactory factory = new CachingConnectionFactory("localhost");
        factory.setPort(5672);
        factory.setUsername("guest");
        factory.setPassword("guest");
        return factory;
    }

    @Bean
    public RabbitTemplate rabbitTemplate() {
        return new RabbitTemplate(connectionFactory());
    }


}
