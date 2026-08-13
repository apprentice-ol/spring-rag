package com.nageoffer.ai.rag.config;

import com.nageoffer.ai.rag.config.properties.RagStorageProperties;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** AWS S3 Client 装配（RustFS MinIO 兼容 S3）。 */
@Slf4j
@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client(RagStorageProperties props) {
        return S3Client.builder()
                .region(Region.of(props.getS3().getRegion()))
                .endpointOverride(URI.create(props.getS3().getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getS3().getAccessKey(), props.getS3().getSecretKey())))
                .forcePathStyle(props.getS3().isPathStyle())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(RagStorageProperties props) {
        return S3Presigner.builder()
                .region(Region.of(props.getS3().getRegion()))
                .endpointOverride(URI.create(props.getS3().getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getS3().getAccessKey(), props.getS3().getSecretKey())))
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                        .pathStyleAccessEnabled(props.getS3().isPathStyle())
                        .build())
                .build();
    }
}
