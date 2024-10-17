package com.company.videouploadapi.queue.publisher;

import com.company.videouploadapi.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate.ConfirmCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;


@Component
@Slf4j
public class VideoPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitAdmin rabbitAdmin;

    @Value("${video.processing.max-retries}")
    private int maxRetries;

    @Value("${video.processing.retry-delay-ms}")
    private long retryDelay;

    public VideoPublisher(RabbitTemplate rabbitTemplate, RabbitAdmin rabbitAdmin) {
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitAdmin = rabbitAdmin;
        this.rabbitTemplate.setConfirmCallback(confirmCallback());
    }

    public void publishVideo(String jsonMessage, String resolutionType) {
        // Declare the queues before publishing
        declareQueues();

        // Determine the routing key based on the resolutionType
        String routingKey = resolutionType.equalsIgnoreCase("480p") ? RabbitMQConfig.ROUTING_KEY_480P : RabbitMQConfig.ROUTING_KEY_720P;

        // Try to send the message with retries
        sendWithRetries(jsonMessage, routingKey, 0);
    }


    private void declareQueues() {
        Queue video480pQueue = QueueBuilder.durable(RabbitMQConfig.QUEUE_480P)
                .withArgument("x-dead-letter-exchange", RabbitMQConfig.DLX_EXCHANGE) // Ensure DLX is set
                .build();
        Queue video720pQueue = QueueBuilder.durable(RabbitMQConfig.QUEUE_720P)
                .withArgument("x-dead-letter-exchange", RabbitMQConfig.DLX_EXCHANGE) // Ensure DLX is set
                .build();

        rabbitAdmin.declareQueue(video480pQueue);
        rabbitAdmin.declareQueue(video720pQueue);
    }


    private void sendWithRetries(String message, String routingKey, int attempt) {
        try {
            // Publish the video path to the exchange
            rabbitTemplate.convertAndSend(RabbitMQConfig.VIDEO_PROCESSING_EXCHANGE, routingKey, message);
            log.info("Video published to Exchange with Routing Key: {}", routingKey);
        } catch (Exception e) {
            log.error("Failed to publish message: {}", e.getMessage());
            // If max attempts are reached, send to DLX
            if (attempt > maxRetries) {
                log.info("Max retries reached. Sending to DLX.");
                rabbitTemplate.convertAndSend(RabbitMQConfig.DLX_EXCHANGE, routingKey, message);
            } else {
                // Retry sending the message after a delay
                try {
                    Thread.sleep(retryDelay);
                    sendWithRetries(message, routingKey, attempt + 1);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("Retry interrupted: {}", ie.getMessage());
                }
            }
        }
    }

    private ConfirmCallback confirmCallback() {
        return (correlationData, ack, cause) -> {
            if (ack) {
                log.info("Message acknowledged by RabbitMQ.");
            } else {
                log.error("Message not acknowledged. Cause: {}", cause);
            }
        };
    }

}


