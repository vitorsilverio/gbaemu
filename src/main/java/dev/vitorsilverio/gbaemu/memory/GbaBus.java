package dev.vitorsilverio.gbaemu.memory;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.gbaemu.core.MemorySpace;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// Barramento de memória do GBA.
///
/// Dispatch O(1) por página via `PagedAddressSpace` (arm-jitter, tasks C3/C6): cada
/// dispositivo registrado com [#add] é classificado, na primeira montagem (preguiçosa,
/// refeita a cada [#add] subsequente), num dos blocos fixos do mapa de memória do GBA.
/// EWRAM/IWRAM viram páginas de RAM verdadeiras (`mapRam`/`mapMirror` — acesso direto ao
/// array, sem despacho virtual). As demais regiões (I/O, BIOS, paleta/VRAM/OAM, ROM,
/// EEPROM, SRAM) viram um handler por bloco, envolvendo os MESMOS objetos de hoje sem
/// alterar nenhum comportamento observável — só a busca de "qual bloco atende este
/// endereço" deixa de ser uma varredura linear sobre todos os dispositivos e passa a ser
/// uma leitura de tabela.
public final class GbaBus implements AddressSpace {

    private static final int WAITCNT = 0x04000204;

    /// Deslocamento de página da tabela O(1): páginas de 32KB. As 4 tabelas paralelas do
    /// `PagedAddressSpace` (`ramPages`/`handlerPages`/`waitstates`/`containsCode`) cobrem
    /// os 32 bits inteiros de endereço — páginas menores (ex.: 4KB) ampliam a tabela ~8x
    /// (de ~131072 para ~1048576 entradas) sem ganho de correção (nenhum bloco deste
    /// barramento precisa de granularidade menor que o período de espelho de IWRAM, o mais
    /// fino) e mediram uma REGRESSÃO no bench headless dos 5 jogos — provável perda de
    /// localidade de cache nas tabelas grandes e esparsas nos acessos mais quentes
    /// (EWRAM/IWRAM). 32KB é o maior valor que ainda expressa o período de espelho de
    /// IWRAM como UMA página só (sem precisar de `mapMirror` dentro do próprio bloco
    /// primário) e mantém EWRAM/BIOS/I-O como múltiplos exatos de página.
    private static final int PAGE_SHIFT = 15;

    /// Tamanho, em bytes, do bloco de páginas reservado para I/O — arredondado para cima
    /// a partir de [GbaMemoryRegion#IO] (que termina em 0x040003FE, não alinhado a
    /// página) até o próximo múltiplo de página. Endereços reais fora dos registradores
    /// conhecidos, mas dentro desse bloco, continuam caindo no barramento aberto — o
    /// arredondamento não muda nenhum endereço observável.
    private static final int IO_BLOCK_SIZE = 1 << PAGE_SHIFT;

    /// Início da metade de GAME_PAK_WS2 respondida pelo chip EEPROM serial quando o
    /// cartucho usa esse tipo de save (GBATEK; ver
    /// [dev.vitorsilverio.gbaemu.cartridge.GbaEepromSave]): o bloco 0x0C000000-0x0DFFFFFF
    /// inteiro é ROM, mas o EEPROM se sobrepõe à segunda metade (0x0D).
    private static final int GAME_PAK_EEPROM_WINDOW_START = 0x0D000000;
    private static final int GAME_PAK_EEPROM_WINDOW_SIZE = 0x01000000;
    private static final int GAME_PAK_LOW_START = GbaMemoryRegion.GAME_PAK_WS0.start();
    private static final int GAME_PAK_LOW_SIZE = GAME_PAK_EEPROM_WINDOW_START - GAME_PAK_LOW_START;

    /// Regiões cujo bloco inteiro (início a fim do enum) vira um handler único — em geral
    /// um único dispositivo dono de toda a região; a busca em [#probeBucketMembers] ainda
    /// cobre o caso de mais de um dispositivo reivindicar os extremos do bloco.
    private static final GbaMemoryRegion[] HANDLER_REGIONS = {
            GbaMemoryRegion.BIOS,
            GbaMemoryRegion.PALETTE,
            GbaMemoryRegion.VRAM,
            GbaMemoryRegion.OAM,
            GbaMemoryRegion.SRAM,
    };

    private final List<MemorySpace> spaces = new ArrayList<>();
    private MemorySpace waitcntSpace;
    private int openBusValue;

    // Tabela de páginas montada preguiçosamente a partir de `spaces`; invalidada (posta a
    // null) a cada `add` e reconstruída na primeira leitura/escrita seguinte.
    private PagedAddressSpace pages;

    public void add(MemorySpace space) {
        spaces.add(space);
        pages = null;
        waitcntSpace = null;
    }

    public <T extends MemorySpace> Optional<T> find(Class<T> type) {
        return spaces.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst();
    }

    public int openBusValue() {
        return openBusValue;
    }

    public void setOpenBusValue(int value) {
        this.openBusValue = value;
    }

    @Override
    public int read8(int address) {
        return pages().read8(address);
    }

    @Override
    public int read16(int address) {
        if (GbaMemoryRegion.SRAM.contains(address)) {
            // SRAM/Flash sit on an 8-bit bus: a 16-bit read fetches one byte and
            // mirrors it across both lanes (byte * 0x0101).
            int b = read8(address & ~1) & 0xFF;
            return b | (b << 8);
        }
        int aligned = address & ~1;
        return pages().read16(aligned);
    }

    @Override
    public int read32(int address) {
        if (GbaMemoryRegion.SRAM.contains(address)) {
            // 8-bit bus: a 32-bit read mirrors the single byte across all lanes.
            int b = read8(address & ~3) & 0xFF;
            return b * 0x01010101;
        }
        int aligned = address & ~3;
        int value = pages().read32(aligned);
        return Integer.rotateRight(value, (address & 3) * 8);
    }

    @Override
    public void write8(int address, int value) {
        pages().write8(address, value & 0xFF);
    }

    @Override
    public void write16(int address, int value) {
        if (GbaMemoryRegion.SRAM.contains(address)) {
            // 8-bit bus: only the byte facing the chip is written, selected by the
            // low address bits (value rotated right by 8*(addr&3)).
            write8(address, value >>> (8 * (address & 3)));
            return;
        }
        int aligned = address & ~1;
        pages().write16(aligned, value & 0xFFFF);
    }

    @Override
    public void write32(int address, int value) {
        if (GbaMemoryRegion.SRAM.contains(address)) {
            // 8-bit bus: a 32-bit store also writes a single byte (the lane facing
            // the chip), not all four.
            write8(address, value >>> (8 * (address & 3)));
            return;
        }
        int aligned = address & ~3;
        pages().write32(aligned, value);
    }

    @Override
    public int accessCycles(int address, int sizeBytes, MemoryAccessType type) {
        GbaMemoryRegion region = GbaMemoryRegion.regionFor(address);
        if (region == null) return 0;
        int cycles = switch (region) {
            case EWRAM -> 3;
            case GAME_PAK_WS0, GAME_PAK_WS1, GAME_PAK_WS2 -> gamePakCycles(region, sizeBytes);
            case SRAM -> 5;
            default -> 1;
        };
        return Math.max(0, cycles - 1);
    }

    private int gamePakCycles(GbaMemoryRegion region, int sizeBytes) {
        int waitcnt = readWaitcnt();
        int waitstate = switch (region) {
            case GAME_PAK_WS0 -> (waitcnt >>> 2) & 0x3;
            case GAME_PAK_WS1 -> (waitcnt >>> 5) & 0x3;
            case GAME_PAK_WS2 -> (waitcnt >>> 8) & 0x3;
            default -> 0;
        };
        int cycles = switch (waitstate) {
            case 0 -> 4;
            case 1 -> 3;
            case 2 -> 2;
            case 3 -> 8;
            default -> throw new IllegalStateException("Invalid waitstate: " + waitstate);
        };
        return sizeBytes == 4 ? cycles * 2 : cycles;
    }

    /// WAITCNT is read on every cartridge access to derive wait states; resolve the
    /// owning space once and read it directly instead of scanning the bus each time.
    ///
    /// Os ciclos de GAME_PAK dependem de WAITCNT em tempo de execução (o jogo pode
    /// reprogramá-lo), então esse cálculo NÃO é uma tabela estática por página como o
    /// `PagedAddressSpace` oferece — continua igual ao comportamento anterior à
    /// migração desta task, por região, para não deslocar o timing do emulador.
    private int readWaitcnt() {
        MemorySpace space = waitcntSpace;
        if (space == null) {
            for (MemorySpace candidate : spaces) {
                if (candidate.contains(WAITCNT)) {
                    space = candidate;
                    break;
                }
            }
            waitcntSpace = space;
        }
        return space != null ? space.readHalfWord(WAITCNT) & 0xFFFF : 0;
    }

    // ── montagem da tabela de páginas ───────────────────────────────────────────────

    private PagedAddressSpace pages() {
        if (pages == null) {
            pages = rebuild();
        }
        return pages;
    }

    private PagedAddressSpace rebuild() {
        PagedAddressSpace built = new PagedAddressSpace(PAGE_SHIFT, new MemorySpaceGroup(List.of()));

        GbaEwram ewram = firstOfType(GbaEwram.class);
        if (ewram != null) {
            mapMirroredRam(built, GbaMemoryRegion.EWRAM, ewram.bytes());
        }
        GbaIwram iwram = firstOfType(GbaIwram.class);
        if (iwram != null) {
            mapMirroredRam(built, GbaMemoryRegion.IWRAM, iwram.bytes());
        }

        for (GbaMemoryRegion region : HANDLER_REGIONS) {
            int size = region.end() - region.start() + 1;
            mapHandlerBucket(built, region.start(), size, probeBucketMembers(region.start(), region.end()));
        }
        mapIoBucket(built);
        mapHandlerBucket(built, GAME_PAK_LOW_START, GAME_PAK_LOW_SIZE,
                probeBucketMembers(GAME_PAK_LOW_START, GAME_PAK_EEPROM_WINDOW_START - 1));
        mapHandlerBucket(built, GAME_PAK_EEPROM_WINDOW_START, GAME_PAK_EEPROM_WINDOW_SIZE,
                probeBucketMembers(GAME_PAK_EEPROM_WINDOW_START,
                        GAME_PAK_EEPROM_WINDOW_START + GAME_PAK_EEPROM_WINDOW_SIZE - 1));

        return built;
    }

    /// Copia `backing` para a janela primária da região e espelha o mesmo array (mesma
    /// referência, sem cópia extra) por todo o resto do bloco de 16MB — o análogo, em
    /// páginas de RAM verdadeiras, ao `Math.floorMod` que os dispositivos faziam sozinhos.
    private void mapMirroredRam(PagedAddressSpace built, GbaMemoryRegion region, byte[] backing) {
        built.mapRam(region.start(), backing);
        int period = region.mirrorSize();
        int blockSize = region.end() - region.start() + 1;
        for (int offset = period; offset < blockSize; offset += period) {
            built.mapMirror(region.start() + offset, region.start(), period);
        }
    }

    /// Mapeia um handler arredondando `size` para cima até o próximo múltiplo de página
    /// (ex.: os 16KB reais da BIOS viram 32KB de página) — o preenchimento extra nunca é
    /// reivindicado por nenhum dispositivo real, então cai no barramento aberto do próprio
    /// grupo, exatamente como caía antes da tabela de páginas (nenhum endereço observável
    /// muda de comportamento).
    private void mapHandlerBucket(PagedAddressSpace built, int base, int size, List<MemorySpace> members) {
        int pageSize = built.pageSize();
        int roundedSize = ((size + pageSize - 1) / pageSize) * pageSize;
        built.mapHandler(base, roundedSize, new MemorySpaceGroup(members));
    }

    /// Mapeia o bloco de I/O com uma tabela de posse pré-computada (endereço -> dono),
    /// em vez de depender do `owner()` de [MemorySpaceGroup] varrer `members` a cada
    /// acesso: este bucket sozinho concentra dezenas de dispositivos (timers, DMA, som,
    /// vídeo, teclado, serial) e é o mais acessado do barramento — o perfil da task C8
    /// (fase 2, candidato #4) mostrou `MemorySpaceGroup.owner` como um dos frames mais
    /// quentes do interpretador. A tabela reproduz EXATAMENTE a mesma regra de
    /// prioridade do `owner()` original (primeiro dispositivo de `members`, na ordem
    /// devolvida por [#probeIoMembers], cujo `contains` bate) — só resolvida uma única
    /// vez na montagem em vez de a cada leitura/escrita.
    private void mapIoBucket(PagedAddressSpace built) {
        int start = GbaMemoryRegion.IO.start();
        List<MemorySpace> members = probeIoMembers();
        MemorySpace[] ownerTable = new MemorySpace[IO_BLOCK_SIZE];
        for (int i = 0; i < IO_BLOCK_SIZE; i++) {
            int address = start + i;
            for (MemorySpace space : members) {
                if (space.contains(address)) {
                    ownerTable[i] = space;
                    break;
                }
            }
        }
        built.mapHandler(start, IO_BLOCK_SIZE, new MemorySpaceGroup(members, ownerTable, start));
    }

    /// Devolve os dispositivos (exceto EWRAM/IWRAM, já tratados como RAM) cujo `contains`
    /// bate em `start` OU em `end`. Suficiente para os blocos de handler únicos deste
    /// barramento: cada um deles é ou dono exclusivo do bloco inteiro (contains alinhado
    /// ao início/fim), ou compartilhado por no máximo dois dispositivos cujos ranges
    /// alinham nos mesmos extremos (ROM+EEPROM na janela 0x0D). O bloco de I/O, onde os
    /// dispositivos começam no meio do bloco, usa [#probeIoMembers] em vez disto.
    private List<MemorySpace> probeBucketMembers(int start, int end) {
        List<MemorySpace> members = new ArrayList<>();
        addIfMatches(start, members);
        addIfMatches(end, members);
        return members;
    }

    /// Varre cada endereço do bloco de I/O real (não o bloco de página arredondado) para
    /// montar o grupo — os registradores de som/timer/DMA/etc. não começam no início do
    /// bloco, então checar só os extremos (como [#probeBucketMembers]) perderia a maioria
    /// dos dispositivos. O bloco de I/O é pequeno (1KB), então isto é uma varredura barata
    /// feita uma única vez por reconstrução da tabela.
    private List<MemorySpace> probeIoMembers() {
        List<MemorySpace> members = new ArrayList<>();
        for (int addr = GbaMemoryRegion.IO.start(); addr <= GbaMemoryRegion.IO.end(); addr++) {
            addIfMatches(addr, members);
        }
        return members;
    }

    private void addIfMatches(int address, List<MemorySpace> members) {
        for (MemorySpace space : spaces) {
            if (space instanceof GbaEwram || space instanceof GbaIwram) {
                continue; // já viraram páginas de RAM; nunca entram num grupo de handler.
            }
            if (space.contains(address) && !members.contains(space)) {
                members.add(space);
            }
        }
    }

    private <T> T firstOfType(Class<T> type) {
        for (MemorySpace space : spaces) {
            if (type.isInstance(space)) {
                return type.cast(space);
            }
        }
        return null;
    }

    /// Grupo de dispositivos que competem pelo mesmo bloco de páginas (ex.: os
    /// registradores de I/O, ou EEPROM+ROM na janela 0x0D). Reproduz exatamente o
    /// critério de prioridade que o barramento usava antes da tabela de páginas: o
    /// primeiro dispositivo, na ordem de registro em [GbaBus#add], cujo `contains` bate;
    /// sem nenhum dono, cai no valor de barramento aberto do `GbaBus` externo (classe
    /// interna: acessa `openBusValue` diretamente).
    private final class MemorySpaceGroup implements AddressSpace {
        private final List<MemorySpace> members;
        /// Tabela opcional endereço→dono pré-computada (ver [#mapIoBucket]); `null` nos
        /// buckets pequenos (no máximo 2 membros via [#probeBucketMembers]), onde a
        /// varredura de `members` já é O(1) na prática e não justifica a tabela.
        private final MemorySpace[] fastOwners;
        private final int fastBase;

        MemorySpaceGroup(List<MemorySpace> members) {
            this(members, null, 0);
        }

        MemorySpaceGroup(List<MemorySpace> members, MemorySpace[] fastOwners, int fastBase) {
            this.members = members;
            this.fastOwners = fastOwners;
            this.fastBase = fastBase;
        }

        private MemorySpace owner(int address) {
            if (fastOwners != null) {
                int index = address - fastBase;
                if (index >= 0 && index < fastOwners.length) {
                    return fastOwners[index];
                }
            }
            for (MemorySpace space : members) {
                if (space.contains(address)) {
                    return space;
                }
            }
            return null;
        }

        @Override
        public int read8(int address) {
            MemorySpace space = owner(address);
            if (space != null) {
                return space.readByte(address) & 0xFF;
            }
            return (openBusValue >>> ((address & 3) * 8)) & 0xFF;
        }

        @Override
        public int read16(int address) {
            MemorySpace space = owner(address);
            if (space != null) {
                return space.readHalfWord(address) & 0xFFFF;
            }
            return (openBusValue >>> ((address & 2) * 8)) & 0xFFFF;
        }

        @Override
        public int read32(int address) {
            MemorySpace space = owner(address);
            if (space != null) {
                return space.readWord(address);
            }
            return openBusValue;
        }

        @Override
        public void write8(int address, int value) {
            MemorySpace space = owner(address);
            if (space != null) {
                space.writeByte(address, value);
            }
        }

        @Override
        public void write16(int address, int value) {
            MemorySpace space = owner(address);
            if (space != null) {
                space.writeHalfWord(address, value);
            }
        }

        @Override
        public void write32(int address, int value) {
            MemorySpace space = owner(address);
            if (space != null) {
                space.writeWord(address, value);
            }
        }
    }
}
