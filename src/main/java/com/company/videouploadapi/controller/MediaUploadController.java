package com.company.videouploadapi.controller;

import com.company.videouploadapi.model.enums.ResolutionTypeEnum;
import com.company.videouploadapi.queue.publisher.VideoPublisher;
import com.company.videouploadapi.util.FFMpegService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@Slf4j
public class MediaUploadController {

    private final VideoPublisher videoPublisher;
    private final FFMpegService ffmpegService;

    @PostMapping("/upload")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void uploadFile(@RequestParam("file") MultipartFile file, @RequestParam("resolutionType") ResolutionTypeEnum resolutionType, @RequestParam("postId") Long postId) {
        String filePath = ffmpegService.saveUploadedFileIntoFolder(file);
        // Wait for 1 second after the video is fully uploaded
        try {
            Thread.sleep(1000);// Wait for 1 second
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Video upload interrupted.");
        }
        String message = String.format("{\"postId\": %d, \"video\": \"%s\"}", postId, filePath);
        videoPublisher.publishVideo(message, resolutionType.getValue());
    }

}

