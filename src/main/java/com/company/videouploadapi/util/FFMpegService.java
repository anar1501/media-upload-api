package com.company.videouploadapi.util;

import com.company.videouploadapi.exception.FileShouldNotEmptyException;
import lombok.extern.slf4j.Slf4j;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFmpegExecutor;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;

@Component
@Slf4j
public class FFMpegService {

    @Value("${upload.dir}")
    private String uploadDir;

    private final FFmpeg ffmpeg;
    private final FFprobe ffprobe;

    public FFMpegService() throws IOException {
        this.ffmpeg = new FFmpeg("C:\\ffmpeg\\ffmpeg-master-latest-win64-gpl\\ffmpeg-master-latest-win64-gpl\\bin\\ffmpeg.exe");
        this.ffprobe = new FFprobe("C:\\ffmpeg\\ffmpeg-master-latest-win64-gpl\\ffmpeg-master-latest-win64-gpl\\bin\\ffprobe.exe");
    }

    public String saveUploadedFileIntoFolder(MultipartFile file) {
        if (file.isEmpty()) {
            throw new FileShouldNotEmptyException();
        }
        String filePath = uploadDir + file.getOriginalFilename();
        try {
            File directory = new File(uploadDir);
            if (!directory.exists()) {
                directory.mkdirs();
            }
            File inputFile = new File(directory, file.getOriginalFilename());
            file.transferTo(inputFile);
        } catch (IOException e) {
            log.error("File upload failed: {}", e.getMessage());
        }
        return filePath;
    }

    public String process480Video(String inputPath) {
        log.info("Starting video conversion to 480p for video path: {}", inputPath);
        String outputPath = inputPath.replace(".mp4", "_480p.mp4");
        FFmpegBuilder builder = new FFmpegBuilder()
                .setInput(inputPath)
                .overrideOutputFiles(true)
                .addOutput(outputPath)
                .setVideoResolution(854, 480)
                .setVideoCodec("libx264")
                .setAudioCodec("aac")
                .setStrict(FFmpegBuilder.Strict.EXPERIMENTAL)
                .done();
        FFmpegExecutor executor = new FFmpegExecutor(ffmpeg, ffprobe);
        executor.createJob(builder).run();
        log.info("Video processed and converted to 480p: {}", outputPath);
        return outputPath;
    }

    public String process720Video(String inputPath) {
        log.info("Starting video conversion to 720p for video path: {}", inputPath);
        String outputPath = inputPath.replace(".mp4", "_720p.mp4");
        FFmpegBuilder builder = new FFmpegBuilder()
                .setInput(inputPath)
                .overrideOutputFiles(true)
                .addOutput(outputPath) // Output file path
                .setVideoResolution(1280, 720)
                .setVideoCodec("libx264")
                .setAudioCodec("aac")
                .setStrict(FFmpegBuilder.Strict.EXPERIMENTAL)
                .done();
        FFmpegExecutor executor = new FFmpegExecutor(ffmpeg, ffprobe);
        executor.createJob(builder).run();
        log.info("Video processed and converted to 720p: {}", outputPath);
        return outputPath;
    }

}
