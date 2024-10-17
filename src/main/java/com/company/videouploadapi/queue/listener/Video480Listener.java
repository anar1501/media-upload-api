package com.company.videouploadapi.queue.listener;

import com.company.videouploadapi.config.RabbitMQConfig;
import com.company.videouploadapi.dao.entity.VideoEntity;
import com.company.videouploadapi.dao.repository.VideoRepository;
import com.company.videouploadapi.mapper.VideoDataMapper;
import com.company.videouploadapi.model.request.VideoData;
import com.company.videouploadapi.util.FFMpegService;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class Video480Listener {

    private final RedissonClient redissonClient;
    private final FFMpegService ffMpegService;
    private final VideoRepository videoRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final RabbitTemplate rabbitTemplate;

    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1); // For managing delays
    private ScheduledFuture<?> scheduledTask; // To track the scheduled 1-second task


    @RabbitListener(queues = RabbitMQConfig.QUEUE_480P)
    @Transactional
    public void consume480p(String message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        log.info("Received video processing request from 480p queue: {}", message);

        String lockKey = "video-480-processing-lock";
        String queueName = RabbitMQConfig.QUEUE_480P;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            VideoData videoData = VideoDataMapper.VIDEO_DATA_MAPPER.map(message);

            // Check if Redis lock is already set by 720p consumer
            String currentLock = redisTemplate.opsForValue().get("queue_lock");
            if (queueName.equals(currentLock)) {
                log.info("480p queue is currently processing.");
            } else if ("video-720p-queue".equals(currentLock)) {
                log.info("720p queue has the lock. Waiting 3 seconds before retrying...");

                // Wait for 3 seconds before requeueing the message
                Thread.sleep(3000);

                // Nack the message and requeue with the correct delivery tag
                channel.basicNack(deliveryTag, false, true);
                return; // Exit processing for now, message will be retried later
            }

            // Cancel any previous scheduled task if it exists
            if (scheduledTask != null && !scheduledTask.isDone()) {
                scheduledTask.cancel(false); // Cancel the previous task
                redisTemplate.delete("queue_lock"); // Release the Redis lock from the previous task
                log.info("Canceled previous 1-second delay due to new message arrival.");
            }

            // Try to acquire the lock for the 480p queue
            if (lock.tryLock(30, 10, TimeUnit.SECONDS)) {
                redisTemplate.opsForValue().set("queue_lock", queueName, 10, TimeUnit.SECONDS); // Set with expiration

                // Process the video into 480p format
                String processedVideoPath = ffMpegService.process480Video(videoData.video());

                // Save video details to the database
                saveVideoData(videoData.postId(), processedVideoPath);

                // Schedule a task to send data to 720p queue after 1 second
                scheduledTask = scheduledExecutorService.schedule(() -> {
                    try {
                        sendTo720pQueue(videoData.postId(), processedVideoPath);
                        deleteVideoData(videoData.postId()); // Delete the data after sending to 720p queue
                        redisTemplate.delete("queue_lock"); // Release the lock after processing
                        log.info("Lock released after successful 480p processing.");
                    } catch (Exception e) {
                        log.error("Error while sending to 720p or deleting data: {}", e.getMessage());
                    }
                }, 1, TimeUnit.SECONDS); // Wait for 1 second before sending the data
            } else {
                log.error("Unable to acquire lock, another consumer is processing.");
            }

            // Acknowledge the message once the video is processed
            channel.basicAck(deliveryTag, false);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Lock interrupted: {}", e.getMessage());
        } catch (IOException e) {
            log.error("Error processing video: {}", e.getMessage());
            try {
                channel.basicNack(deliveryTag, false, true); // Nack and requeue if there's an error
            } catch (IOException ioException) {
                log.error("Error during nack: {}", ioException.getMessage());
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("480p Lock released.");
            }
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
        log.info("480p Video details saved to database: PostId = {}, Video Path = {}", postId, videoPath);
    }

    @Transactional
    public void deleteVideoData(Long postId) {
        videoRepository.deleteById(postId); // Assuming there's a method to delete by postId
        log.info("Video details deleted from database for PostId: {}", postId);
    }

    public void sendTo720pQueue(Long postId, String videoPath) {
        String message = String.format("{ \"postId\": %d, \"video\": \"%s\" }", postId, videoPath);
        rabbitTemplate.convertAndSend(RabbitMQConfig.VIDEO_PROCESSING_EXCHANGE, "720p-process", message);
        log.info("Message sent to 720p queue: {}", message);
    }
}
