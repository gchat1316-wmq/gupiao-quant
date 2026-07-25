package com.quant.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "swing_event")
public class SwingEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "watch_id", nullable = false)
  private Long watchId;

  @Column(name = "position_id")
  private Long positionId;

  @Column(name = "event_type", nullable = false, length = 40)
  private String eventType;

  @Column(name = "from_status", length = 32)
  private String fromStatus;

  @Column(name = "to_status", length = 32)
  private String toStatus;

  @Column(name = "payload_json", columnDefinition = "JSON")
  private String payloadJson;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;
}
