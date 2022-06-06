package com.walmart.caspr.service.impl;

import com.walmart.caspr.exception.CasprAPIException;
import com.walmart.caspr.model.HttpRequest;
import com.walmart.caspr.model.api.ResponseBody;
import com.walmart.caspr.service.CasperService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.function.Tuple2;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class CasperServiceImpl implements CasperService {

    private final Logger log = Loggers.getLogger(CasperServiceImpl.class);
    @Autowired
    private WebClient webClient;

    @Override
    public Mono<ResponseBody> postPayloadToCasper(HttpRequest incomingMessage, String message) {
        webClient = WebClient.create(incomingMessage.getUrl());
        return Mono.just(incomingMessage)
                .flatMap(payload -> webClient.post()
                        .body(Mono.just(payload.getPayLoad()), String.class)
                        .headers(httpHeaders -> {
                            if (Objects.nonNull(payload.getHeaders())) {
                                httpHeaders.setAll(payload.getHeaders().entrySet()
                                        .stream().collect(Collectors.toMap(Map.Entry::getKey, e -> (String) e.getValue())));
                            }
                        })
                        .cookies(cookies -> {
                            if (Objects.nonNull(payload.getCookies())) {
                                cookies.setAll(payload.getCookies().entrySet()
                                        .stream().collect(Collectors.toMap(Map.Entry::getKey, e -> (String) e.getValue())));
                            }
                        })
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .onStatus(HttpStatus::is4xxClientError, clientResponse -> {
                            throw Exceptions.propagate(new CasprAPIException("Caspr API is not reachable.."));
                        })
                        .onStatus(HttpStatus::is5xxServerError, clientResponse -> {
                            throw Exceptions.propagate(new CasprAPIException("Caspr API internal server error."));
                        })
                        .bodyToMono(ResponseBody.class)
                        .elapsed()
                        .doOnNext(tuple -> log.info("postPayloadToCasper:: time elapsed for Capsr API : {} ms .", tuple.getT1()))
                        .map(Tuple2::getT2)
                )
                .doOnError(throwable -> {
                    throw Exceptions.propagate(throwable);
                })
                .doOnNext(responseBody -> log.info("postPayloadToCasper:: responseBody start date {} end date {}",
                        responseBody.getPayload() != null ? responseBody.getPayload().getStartDate() : "",
                        responseBody.getPayload() != null ? responseBody.getPayload().getEndDate() : ""));

    }
}
