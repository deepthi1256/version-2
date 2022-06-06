package com.walmart.caspr.service.impl;

import com.walmart.caspr.model.ThreadPoolConfig;
import com.walmart.caspr.service.CasprEventProcessor;
import com.walmart.caspr.service.ReactiveKafkaEventListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.reactive.ReactiveKafkaConsumerTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.retry.Retry;

import java.time.Duration;

@Service
public class ReactiveKafkaEventListenerImpl implements ReactiveKafkaEventListener {

    private final Logger log = Loggers.getLogger(ReactiveKafkaEventListenerImpl.class);
    private final ReactiveKafkaConsumerTemplate<String, String> requestReactiveKafkaConsumerTemplate;
    private final CasprEventProcessor casprEventProcessor;
    private final ThreadPoolConfig threadPoolConfig;
    @Value("${spring.kafka.consumer.max.retries}")
    private int maxRetries;
    @Value("${spring.kafka.consumer.max.retry.delay}")
    private long maxRetryDelay;

    public ReactiveKafkaEventListenerImpl(ReactiveKafkaConsumerTemplate<String, String> requestReactiveKafkaConsumerTemplate,
                                          CasprEventProcessor casprEventProcessor, ThreadPoolConfig threadPoolConfig) {
        this.requestReactiveKafkaConsumerTemplate = requestReactiveKafkaConsumerTemplate;
        this.casprEventProcessor = casprEventProcessor;
        this.threadPoolConfig = threadPoolConfig;
    }

    @Override
    @EventListener(ApplicationReadyEvent.class)
    public void processEvent() {
        log.info("processEvent:: started..");

        Scheduler scheduler = Schedulers.newBoundedElastic(threadPoolConfig.getThreadCap(),
                threadPoolConfig.getQueuedTaskCap(), threadPoolConfig.getThreadPrefix(), threadPoolConfig.getTtlSeconds());

        Flux<ReceiverRecord<String, String>> receiverRecordFlux = Flux.defer(requestReactiveKafkaConsumerTemplate::receive);
        receiverRecordFlux.groupBy(m -> m.receiverOffset().topicPartition())
                .doOnNext(partitionFlux -> log.info("processEvent:: topicPartition {}", partitionFlux.key()))
                .flatMap(partitionFlux -> partitionFlux.subscribeOn(scheduler)
                        .doOnNext(r -> log.info("processEvent:: Record received from offset {} from topicPartition {} with message key {}", r.receiverOffset().topicPartition(), r.key(), r.offset()))
                        .flatMap(this::processRecordRetryLazily)
                        .doOnNext(receiverRecordInfo -> log.info("processEvent:: Record processed from offset {} from topicPartition {} with message key {}", receiverRecordInfo.receiverOffset().offset(), receiverRecordInfo.receiverOffset().topicPartition()))
                        .retryWhen(Retry.backoff(maxRetries, Duration.ofMillis(maxRetryDelay))
                        )
                        .onErrorResume(throwable -> Mono.empty())
                )
                .subscribe(
                        key -> log.info("Successfully consumed messages, key {}", key),
                        error -> log.error("Error while consuming messages ", error));
    }

    private Mono<ReceiverRecord<String, String>> processRecordRetryLazily(ReceiverRecord<String, String> receiverRecord) {
        return Mono.defer(() -> processRecord(receiverRecord)).retry(maxRetries);
    }

    private Mono<ReceiverRecord<String, String>> processRecord(ReceiverRecord<String, String> receiverRecord) {
        return Mono.just(receiverRecord)
                .doOnNext(record -> log.info("processRecord:: processing started"))
                .flatMap(casprEventProcessor::deserializeJsonToHttpRequest)
                .flatMap(casprEventProcessor::submitPayloadToCasprAPI)
                .flatMap(casprEventProcessor::deserializeValidatorJsonToResponseBody)
                .map(casprEventProcessor::validateResponses)
                .doOnSuccess(this::handleSuccess);
    }

    private void handleSuccess(ReceiverRecord<String, String> record) {
        record.receiverOffset().acknowledge();
        log.info("processRecord:: offset {} from topicPartition {} is acknowledged successfully", record.receiverOffset().offset(), record.receiverOffset().topicPartition());
    }

}
