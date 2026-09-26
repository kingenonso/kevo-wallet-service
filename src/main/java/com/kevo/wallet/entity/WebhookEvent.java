package com.kevo.wallet.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "webhook_events")
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_name", nullable = false, length = 50)
    private String providerName;

    @Column(name = "event_id", nullable = false, length = 150)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "signature_valid", nullable = false)
    private boolean signatureValid;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    protected WebhookEvent() {}

    public WebhookEvent(String providerName, String eventId, String eventType, boolean signatureValid) {
        this.providerName = providerName;
        this.eventId = eventId;
        this.eventType = eventType;
        this.signatureValid = signatureValid;
        this.receivedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getProviderName() { return providerName; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public boolean isSignatureValid() { return signatureValid; }
    public Instant getReceivedAt() { return receivedAt; }
}
