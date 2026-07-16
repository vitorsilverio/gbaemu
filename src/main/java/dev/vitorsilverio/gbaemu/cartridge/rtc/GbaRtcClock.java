package dev.vitorsilverio.gbaemu.cartridge.rtc;

import java.time.LocalDateTime;

/// Fonte de data/hora para o [S3511aRtc]. Abstraída (em vez de chamar
/// `LocalDateTime.now()` direto) para permitir um relógio FAKE nos testes de
/// protocolo — ver task D1, teste 1.
@FunctionalInterface
public interface GbaRtcClock {
    LocalDateTime now();

    /// Relógio real do host, usado em produção (v1 do RTC: sem offset persistido — ver
    /// task D1, item 2).
    GbaRtcClock SYSTEM = LocalDateTime::now;
}
