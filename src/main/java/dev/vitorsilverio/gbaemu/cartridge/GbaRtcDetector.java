package dev.vitorsilverio.gbaemu.cartridge;

/// Detecta cartuchos com chip RTC S-3511A (GBATEK "GBA Cart Real-Time Clock (RTC)").
/// Ao contrário do tipo de save, o GBATEK não descreve nenhuma assinatura ASCII para
/// RTC — a única forma de saber é uma lista de jogos conhecidos pelo game code de 4
/// caracteres (mesmo offset/campo que [GbaSaveTypeDetector] usa para o override de save).
public final class GbaRtcDetector {
    private GbaRtcDetector() {
    }

    public static boolean hasRtc(String gameCode) {
        return switch (gameCode) {
            case "AXVE", "AXPE", "BPEE" -> true; // Pokemon Ruby/Sapphire/Emerald
            case "U3IJ", "U32J", "U33J" -> true; // Boktai series
            default -> false;
        };
    }
}
