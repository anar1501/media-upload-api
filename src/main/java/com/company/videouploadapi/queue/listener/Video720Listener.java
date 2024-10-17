package com.company.videouploadapi.queue.listener;

import com.company.videouploadapi.config.RabbitMQConfig;
import com.company.videouploadapi.dao.entity.VideoEntity;
import com.company.videouploadapi.dao.repository.VideoRepository;
import com.company.videouploadapi.mapper.VideoDataMapper;
import com.company.videouploadapi.model.request.VideoData;
import com.company.videouploadapi.util.FFMpegService;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class Video720Listener {
    private final RedissonClient redissonClient;
    private final FFMpegService ffmpegService;
    private final VideoRepository videoRepository;
    private final RedisTemplate<String, String> redisTemplate;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_720P)
    @Transactional
    public void consume720p(String message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException, InterruptedException {
        log.info("Received video processing request from 720p queue: {}", message);

        // Introduce a 1-second delay before processing
        try {
            Thread.sleep(1000); // Wait 1 second before processing
        } catch (InterruptedException e) {
            log.error("Interrupted while waiting: {}", e.getMessage());
            Thread.currentThread().interrupt();
            channel.basicNack(deliveryTag, false, true); // Nack the message and requeue
            return;
        }

        // Check if there are new messages in the 480p queue
        int messageCount480p = check480pQueue(channel);
        if (messageCount480p > 0) {
            log.info("Messages found in 480p queue. Postponing 720p processing.");
            channel.basicNack(deliveryTag, false, true); // Requeue the current message
            return;
        }

        String lockKey = "video-720-processing-lock";
        String queueLockKey = "queue_lock";  // The Redis key for managing queue ownership
        RLock lock = redissonClient.getLock(lockKey);

        int maxAttempts = 10; // Maximum number of attempts
        int attemptCount = 0; // Current attempt count

        while (attemptCount < maxAttempts) {
            try {
                VideoData videoData = VideoDataMapper.VIDEO_DATA_MAPPER.map(message);

                // Use Redis to manage queue lock ownership between 480p and 720p
                Boolean lockAcquired = redisTemplate.opsForValue().setIfAbsent(queueLockKey, "720p-queue");
                if (Boolean.FALSE.equals(lockAcquired)) {
                    log.info("480p has already set the Redis lock. Waiting 3 seconds before requeuing.");
                    Thread.sleep(3000); // Wait for 3 seconds
                    channel.basicNack(deliveryTag, false, true); // Requeue the current message
                    return;
                }

                // Use Redisson lock directly for locking mechanism
                if (lock.tryLock(30, 10, TimeUnit.SECONDS)) {
                    try {
                        log.info("720p Lock acquired, processing video...");

                        // Send data to FFmpeg to process the video in 720p
                        String processedVideoPath = ffmpegService.process720Video(videoData.video());
                        saveVideoData(videoData.postId(), processedVideoPath);

                        // Acknowledge the message once processing is done
                        channel.basicAck(deliveryTag, false); // Acknowledge the message
                        return; // Exit the method after successful processing
                    } finally {
                        lock.unlock(); // Always release the lock in the finally block
                        redisTemplate.delete(queueLockKey);  // Release the Redis queue lock
                        log.info("720p Lock released.");
                    }
                } else {
                    log.error("Unable to acquire lock, another consumer is processing.");
                    channel.basicNack(deliveryTag, false, true); // Requeue the message
                    return; // Exit the method after requeuing
                }
            } catch (Exception e) {
                attemptCount++;
                log.error("Error processing video: {}, Attempt: {}", e.getMessage(), attemptCount);
                if (attemptCount >= maxAttempts) {
                    log.error("Max attempts reached, sending message to dead letter exchange.");
                    channel.basicNack(deliveryTag, false, false); // Send to dead letter exchange
                } else {
                    Thread.sleep(5000); // Delay before retrying
                }
            }
        }
    }

    private int check480pQueue(Channel channel) {
        try {
            // Check the message count of the 480p queue
            AMQP.Queue.DeclareOk response = channel.queueDeclarePassive(RabbitMQConfig.QUEUE_480P);
            int messageCount = response.getMessageCount();
            log.info("480p queue message count: {}", messageCount);
            return messageCount;
        } catch (IOException e) {
            log.error("Failed to check 480p queue message count: {}", e.getMessage());
            return 0;
        }
    }

    @Transactional
    public void saveVideoData(Long postId, String videoPath) {
        VideoEntity videoEntity = new VideoEntity();
        videoEntity.setVideoPath(videoPath);
        videoEntity.setVideoName(videoPath.substring(videoPath.lastIndexOf("/") + 1));
        videoEntity.setPostId(postId);
        videoEntity.setStatus("PROCESSED");
        videoRepository.save(videoEntity);
        log.info("720p Video details saved to database: PostId = {}, Video Path = {}", postId, videoPath);
    }

}
