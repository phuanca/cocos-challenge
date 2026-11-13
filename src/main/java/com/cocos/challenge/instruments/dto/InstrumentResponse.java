package com.cocos.challenge.instruments.dto;

public record InstrumentResponse(
        Integer id,
        String ticker,
        String name,
        String type
) {
}