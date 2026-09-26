package com.kevo.wallet.repository;

import com.kevo.wallet.entity.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {
    Optional<WebhookEvent> findByProviderNameAndEventId(String providerName, String eventId);
}
