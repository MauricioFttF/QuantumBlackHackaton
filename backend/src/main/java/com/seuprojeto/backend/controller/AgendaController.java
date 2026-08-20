package com.seuprojeto.backend.controller;

import com.seuprojeto.backend.dto.AgendaRecommendRequest;
import com.seuprojeto.backend.dto.AgendaRecommendResponse;
import com.seuprojeto.backend.model.AuthenticatedUser;
import com.seuprojeto.backend.service.AgendaRecommendationService;
import com.seuprojeto.backend.web.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agenda")
public class AgendaController {

    private final AgendaRecommendationService recommendationService;
    private final CurrentUser currentUser;

    public AgendaController(AgendaRecommendationService recommendationService, CurrentUser currentUser) {
        this.recommendationService = recommendationService;
        this.currentUser = currentUser;
    }

    /**
     * A ranked, conflict-free itinerary. Authenticated like {@code /api/chat}: it spends embedding
     * quota and it reads the caller's stored profile, so it needs to know who is asking.
     */
    @PostMapping("/recommend")
    public AgendaRecommendResponse recommend(@RequestBody(required = false) AgendaRecommendRequest request,
                                             HttpServletRequest httpRequest) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Envie um corpo com 'interests' ou use uma conta que já tenha histórico");
        }

        AuthenticatedUser user = currentUser.require(httpRequest);
        if (request.userId() != null && !request.userId().isBlank()
                && !request.userId().equals(user.conversationKey())) {
            // Neither trusted nor quietly ignored: a client sending someone else's id is either
            // confused or probing, and both deserve to be told.
            throw new IllegalArgumentException(
                    "O campo 'userId' não corresponde à conta autenticada; omita-o — a identidade "
                            + "vem do token.");
        }

        return recommendationService.recommend(user.conversationKey(), request);
    }
}
