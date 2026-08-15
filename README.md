# gbaemu

Emulador de Game Boy Advance em Java, iniciado a partir das experiencias do `gbcemu` e usando a CPU ARM/THUMB do projeto `arm-jitter`.

## Referencia tecnica

A referencia principal de desenvolvimento e o GBATEK:

- Original: <https://problemkaputt.de/gbatek.htm>
- Mirror navegavel usado durante o bootstrap: <https://mgba-emu.github.io/gbatek/>

Para o GBA, o GBATEK cumpre o mesmo papel que o Pan Docs teve no projeto de Game Boy: cada subsistema deve nascer com testes automatizados e com comportamento rastreavel ate a documentacao.

## Estado atual

O emulador roda jogos comerciais de ponta a ponta. Jogos validados em gameplay real
(todos ≥2x realtime em modo headless): **Pokemon FireRed** (overworld, batalhas, menus,
save), **Super Mario World: Super Mario Advance 2**, **Castlevania: Aria of Sorrow**,
**Metroid Fusion** e **Mario Kart: Super Circuit**.

### CPU

- `ArmCore` ARMv4T do `arm-jitter`, backend **interpretado por padrao** (fidelidade de
  IRQ por instrucao; no GBA a velocidade empata com o JIT) com backend JIT ASM disponivel.
- Codigo automodificado suportado: o bus e envolvido em `InvalidationAwareAddressSpace`
  e a invalidacao de blocos JIT e O(1) (page-indexed).
- Stub GDB do `arm-jitter` para depurar o codigo guest.

### Video (PPU)

- Modos 0-5 completos: BG text e affine, tilemaps 4bpp/8bpp, scroll, flip, screen sizes,
  prioridade; OBJ regular e affine (1D/2D, double-size).
- Windows WIN0/WIN1/OBJWIN, alpha blending e brightness.
- Timing por scanline com VCOUNT, IRQs de V-Blank/H-Blank/VCOUNT-match; HDMA disparado
  apenas nas 160 linhas visiveis (hardware-correct).
- Falta apenas mosaic.

### Audio

- 4 canais PSG (clock GBA correto) + 2 FIFOs DirectSound A/B alimentados por timers/DMA.
- Mixagem digital com filtro high-pass (remove DC offset); mute/volume por canal na GUI.

### DMA, timers e sistema

- DMA0-3 com disparo imediato/VBlank/HBlank/special (FIFO de audio), executado em tempo
  de instrucao (immediate DMAs hardware-correct) e endpoints alinhados ao tamanho da
  transferencia.
- Timers TM0-3 com reload, prescaler, cascata e IRQ; keypad com IRQ; IE/IF/IME;
  waitstates via `WAITCNT`; `POSTFLG`/`HALTCNT` com halt e `IntrWait`/`VBlankIntrWait`
  acordando somente nos IRQs corretos.

### Cartucho e saves

- Parsing de header, deteccao de save type por assinatura (SRAM, FLASH, FLASH512,
  FLASH1M, EEPROM) com overrides para jogos que mentem a assinatura.
- Persistencia em `.sav` ao lado da ROM (`GbaSaveFile`/`CartridgeBackup`).
- RTC S-3511A via GPIO de cartucho com deteccao automatica (Pokemon Emerald, Boktai).

### BIOS

- BIOS HLE completa via SWI `0x00..0x2A` (reset, math, copia, affine, unpack/decompress,
  audio) — nao precisa de dump para jogar.
- BIOS real suportada (boot completo funciona; a animacao do boot ainda fica lenta e
  entrecortada — pendencia conhecida D6).

### GUI (Swing)

Aberta por `Main` sem argumentos. Tudo configuravel por menus, sem parametros de CLI:

- ROMs recentes, Settings (BIOS real ou HLE, video, audio).
- Controles configuraveis de teclado + **gamepad** (input4j, aba Controls).
- Save states de maquina completa (`.ss` v2): F5/F8, menu State e para arquivo.
- Frame pacing com cap de velocidade; mute/volume por canal.
- **Multiplayer por link cable**: serial SIO sobre TCP, 2 e 4 jogadores (host-relay).
- Janelas de debug de CPU, PPU e audio.

## Build

Compilar e testar com **JBR 25** (a JDK do IntelliJ), nao o JDK do sistema, com a
`arm-jitter` instalada no Maven local:

```bash
mvn -f ../arm-jitter/pom.xml install
mvn test
```

## Uso

Abrir a GUI (forma recomendada de jogar):

```bash
mvn exec:java -Dexec.mainClass=dev.vitorsilverio.gbaemu.Main
```

Sem argumentos (ou com `--window`) o `Main` abre a GUI; com `--rom` e sem `--window`
roda headless. Modo headless para testes/depuracao:

```bash
mvn exec:java -Dexec.mainClass=dev.vitorsilverio.gbaemu.Main -Dexec.args="--rom game.gba --steps 1800000 --frame frame.ppm --debug-video"
```

Flags principais: `--bios`/`--real-bios` (BIOS real), `--steps`/`--cycles`, `--frame`
(PPM), `--frame-count`/`--frame-step-cycles` (sequencia de frames), `--debug-video`,
`--debug-state`, `--trace-cpu N`/`--trace-cpu-tail N` (trace de PC/registradores/ciclos).
`--help` imprime a lista completa.

Criar uma instancia programatica:

```java
byte[] rom = Files.readAllBytes(Path.of("game.gba"));
GbaConsole console = GbaConsole.fromRom(rom); // skip BIOS, estado pos-BIOS
console.stepCpu(1);
int[] argb = console.renderFrame();
```

Tambem existem `GbaConsole.fromBiosAndRom(...)` (BIOS real) e
`GbaConsole.fromBiosAndRomHle(...)` (BIOS carregada + SWI HLE).

## Mapa de memoria (GBATEK)

- BIOS `00000000-00003FFF`
- EWRAM `02000000-0203FFFF`, espelhada na janela `02000000-02FFFFFF`
- IWRAM `03000000-03007FFF`, espelhada na janela `03000000-03FFFFFF`
- I/O `04000000-040003FE`
- Palette RAM `05000000-050003FF`, espelhada
- VRAM `06000000-06017FFF`, espelhada em blocos de 128 KiB
- OAM `07000000-070003FF`, espelhada
- Game Pak ROM `08000000-0DFFFFFF` nas tres janelas de wait-state
- SRAM `0E000000-0FFFFFFF`, espelhada a cada 64 KiB

## Regras de desenvolvimento

- Sempre criar ou atualizar testes automatizados junto com comportamento novo.
- Manter o README atualizado quando o uso, arquitetura ou escopo mudar.
- Nao executar comandos fora do sandbox; se algum passo exigir acesso externo ou permissao especial, o comando deve ser pedido ao usuario.
- Preferir a API publica do `arm-jitter` para CPU/JIT e isolar detalhes especificos do GBA no `gbaemu`.
- Usar o GBATEK como fonte para memoria, I/O, DMA, timers, PPU, audio, keypad, interrupcoes, BIOS e cartuchos.
- GBATEK descreve GBA e NDS juntos: o GBA e ARM7TDMI/**ARMv4T** — nunca aplicar recursos ARMv5+ aqui.

## Pendencias conhecidas

- Mosaic na PPU (nenhum jogo validado usa de forma visivel).
- Animacao da BIOS real lenta/entrecortada (task D6 no `arm-jitter/tasks/`).
- ROMs de teste `bios.gba`/visual/unsafe do pacote gba-tests adiadas (demais passam).

## Licença

BSD 3-Clause — ver [LICENSE](LICENSE).

Os binários de terceiros usados em testes e execução (BIOS, firmware, ROMs, kernels,
`busybox`) **não** são cobertos por esta licença e não são redistribuídos por este projeto
salvo quando a licença original permitir; ver o `README.md` do diretório correspondente.
