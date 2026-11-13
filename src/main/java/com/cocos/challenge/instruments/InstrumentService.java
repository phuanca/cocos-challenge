package com.cocos.challenge.instruments;

import com.cocos.challenge.instruments.dto.InstrumentResponse;
import com.cocos.challenge.shared.dto.PageResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class InstrumentService {

    private final InstrumentRepository repository;

    public InstrumentService(InstrumentRepository repository) {
        this.repository = repository;
    }

    public PageResponse<InstrumentResponse> search(
            String query,
            int page,
            int size
    ) {
        String normalizedQuery = query.trim();

        long offset = (long) page * size;

        List<InstrumentResponse> content =
                repository.search(normalizedQuery, size, offset);

        long totalElements = repository.count(normalizedQuery);

        long totalPages = (totalElements + size - 1) / size;

        return new PageResponse<>(
                content,
                page,
                size,
                totalElements,
                totalPages
        );
    }
}