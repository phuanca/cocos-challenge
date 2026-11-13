package com.cocos.challenge.portfolio;

import com.cocos.challenge.portfolio.dto.PortfolioResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/{userId}/portfolio")
public class PortfolioController {

    private final PortfolioService service;

    public PortfolioController(PortfolioService service) {
        this.service = service;
    }

    @GetMapping
    public PortfolioResponse getPortfolio(@PathVariable int userId) {
        return service.getPortfolio(userId);
    }
}
