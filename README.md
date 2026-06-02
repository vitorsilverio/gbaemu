# gbaemu

Emulador de Game Boy Advance em Java, iniciado a partir das experiencias do `gbcemu` e usando a CPU ARM/THUMB do projeto `arm-jitter`.

## Referencia tecnica

A referencia principal de desenvolvimento e o GBATEK:

- Original: <https://problemkaputt.de/gbatek.htm>
- Mirror navegavel usado durante o bootstrap: <https://mgba-emu.github.io/gbatek/>

Para o GBA, o GBATEK cumpre o mesmo papel que o Pan Docs teve no projeto de Game Boy: cada subsistema deve nascer com testes automatizados e com comportamento rastreavel ate a documentacao.

## Estado atual

- Projeto Maven Java 25.
- Dependencia local em `dev.vitorsilverio:arm-jitter:1.0`.
- Barramento `GbaMemory` implementando `AddressSpace` do `arm-jitter`.
- Boot por ROM direto ou por BIOS+ROM. Na CLI, `--bios` carrega a BIOS mas usa boot HLE por padrao; `--real-bios` força o PC inicial em `00000000` para depurar a BIOS real.
- Skip BIOS explicito em `GbaConsole.fromRom(...)`, com PC em `08000000`, stack inicial e `POSTFLG`.
- Renderer inicial `GbaVideo` com framebuffer ARGB de 240x160.
- DMA inicial para DMA0-3, cobrindo disparos imediato/VBlank/HBlank, halfword/word, incremento/decremento/fixo/reload e mascaras de endereco.
- Interrupcoes iniciais com IE/IF/IME, write-one-to-clear em IF, linha externa da CPU e pedidos de LCD/DMA.
- Timers TM0-3 com reload, prescaler, cascata e IRQ-on-overflow.
- Keypad com `KEYINPUT` active-low, `KEYCNT` OR/AND e IRQ de keypad.
- Audio inicial com registradores `SOUND1-4`, `SOUNDCNT_L/H/X`, `SOUNDBIAS` e FIFOs A/B mapeados em I/O.
- Waitstates iniciais ligados a API `AddressSpace.accessCycles` do `arm-jitter`, alimentando `core.cycles()` para sincronizar CPU/LCD/timers.
- Cartucho com parsing do header GBA, titulo, game code, maker code, fixed value e complement check.
- Deteccao de save type por assinatura na ROM: SRAM, FLASH, FLASH512, FLASH1M e EEPROM.
- Save memory inicial com backing SRAM-like, snapshot/load e ligacao na regiao `0E000000`.
- Controle simples de sistema com `POSTFLG`, `HALTCNT` e `WAITCNT`.
- BIOS HLE via callbacks de SWI para skip BIOS/ROM: cobre `SoftReset` ate `SoundGetJumpList` (`0x00..0x2A`), com implementacoes para reset, math, copia, affine, unpack/decompress, filtros Diff e stubs seguros para audio/multiboot enquanto a APU completa evolui.
- Diagnostico de video com estatisticas de `DISPCNT`, modo, cores, pixels nao-backdrop, Palette/VRAM nao-zero e OBJ visiveis.
- Trace publico da CPU do `arm-jitter` integrado ao CLI para capturar janelas iniciais/finais de PC, ARM/THUMB, instrucao, SP/LR/CPSR e ciclos durante o boot.
- PPU implementada ate agora:
  - Modos bitmap 3, 4 e 5 com page select nos modos 4/5.
  - Backgrounds regulares/text BG em modo 0 e BG0/BG1 em modo 1.
  - Scroll `BGxHOFS/BGxVOFS`, tilemaps 4bpp/8bpp, screen sizes 256/512, flip H/V e prioridade basica.
  - Backgrounds affine BG2/BG3 em modos 1/2 com matriz, referencia, wrap e prioridade.
  - OBJ/sprites regulares 4bpp/8bpp, shape/size, flip H/V, prioridade contra BG e mapeamento 1D/2D.
  - OBJ affine inicial com matrizes de OAM e double-size.
  - Timing inicial de LCD com `VCOUNT`, flags de `DISPSTAT`, VBlank/HBlank e eventos para DMA.
  - Ainda faltam mosaic, windows, alpha blending/brightness e timing com IRQ por scanline.
- Mapa inicial de memoria conforme GBATEK:
  - BIOS `00000000-00003FFF`
  - EWRAM `02000000-0203FFFF`, espelhada na janela `02000000-02FFFFFF`
  - IWRAM `03000000-03007FFF`, espelhada na janela `03000000-03FFFFFF`
  - I/O `04000000-040003FE`
  - Palette RAM `05000000-050003FF`, espelhada
  - VRAM `06000000-06017FFF`, espelhada em blocos de 128 KiB
  - OAM `07000000-070003FF`, espelhada
  - Game Pak ROM `08000000-0DFFFFFF` nas tres janelas de wait-state
  - SRAM `0E000000-0FFFFFFF`, espelhada a cada 64 KiB
- Fachada `GbaConsole` conectando `GbaMemory`, `ArmCore` e runtime ARM/THUMB interpretado do `arm-jitter`.

## Uso

Compilar e testar:

```bash
mvn test
```

Executar o entrypoint temporario:

```bash
mvn exec:java -Dexec.mainClass=dev.vitorsilverio.gbaemu.Main -Dexec.args="--bios gba_bios.bin --rom game.gba --steps 1000 --frame boot.ppm"
```

O frame gerado usa o formato PPM binario (`P6`), simples de abrir ou converter em ferramentas de imagem.

Gerar uma sequencia de frames sem trace, avancando a CPU entre capturas:

```bash
mvn exec:java -Dexec.mainClass=dev.vitorsilverio.gbaemu.Main -Dexec.args="--bios gba_bios.bin --rom game.gba --steps 1800000 --frame bios.ppm --frame-count 8 --frame-step-cycles 280896 --debug-video"
```

Abrir uma janela Swing com escala inteira:

```bash
mvn exec:java -Dexec.mainClass=dev.vitorsilverio.gbaemu.Main -Dexec.args="--bios gba_bios.bin --rom game.gba --window --scale 3 --no-frame"
```

Por padrao a janela avanca um frame de LCD por tick (`280896` ciclos). Para desacelerar/acelerar
o bring-up visual, ajuste `--cycles-per-frame N`. `--steps-per-frame N` ainda existe como modo
manual de debug e, quando informado, tem prioridade sobre o avanco por ciclos.

Para investigar tela preta durante o bring-up da CPU/BIOS real, adicione `--real-bios --debug-video`.
Isso imprime periodicamente um resumo como `mode`, `DISPCNT`, camadas habilitadas, quantidade de cores renderizadas,
pixels diferentes do backdrop, Palette/VRAM nao-zero e OBJ visiveis:

```bash
mvn exec:java -Dexec.mainClass=dev.vitorsilverio.gbaemu.Main -Dexec.args="--bios gba_bios.bin --rom game.gba --window --scale 3 --no-frame --debug-video"
```

Para investigar loops de CPU no boot da BIOS, use `--trace-cpu N` para imprimir o inicio e
`--trace-cpu-tail N` para guardar e imprimir apenas as ultimas instrucoes ao fim do run.
O trace inclui PC, ARM/THUMB, opcode, tipo da instrucao, proximo PC, `r0-r12`, SP, LR, CPSR e ciclos:

```bash
mvn exec:java -Dexec.mainClass=dev.vitorsilverio.gbaemu.Main -Dexec.args="--bios gba_bios.bin --rom game.gba --steps 600 --no-frame --trace-cpu 40 --trace-cpu-tail 80"
```

Criar uma instancia programatica:

```java
byte[] bios = Files.readAllBytes(Path.of("gba_bios.bin"));
byte[] rom = Files.readAllBytes(Path.of("game.gba"));
GbaConsole console = GbaConsole.fromBiosAndRom(bios, rom);
console.stepCpu(1);
int[] argb = console.renderFrame();
```

Tambem existe `GbaConsole.fromRom(rom)` para skip BIOS, iniciando em `08000000` com estado inicial pos-BIOS.

## Regras de desenvolvimento

- Sempre criar ou atualizar testes automatizados junto com comportamento novo.
- Manter o README atualizado quando o uso, arquitetura ou escopo mudar.
- Nao executar comandos fora do sandbox; se algum passo exigir acesso externo ou permissao especial, o comando deve ser pedido ao usuario.
- Preferir a API publica do `arm-jitter` para CPU/JIT e isolar detalhes especificos do GBA no `gbaemu`.
- Usar o GBATEK como fonte para memoria, I/O, DMA, timers, PPU, audio, keypad, interrupcoes, BIOS e cartuchos.
- A BIOS real deve ser tratada como programa de teste de integracao: quando ela parar por instrucao ARM ainda nao implementada, a correcao deve acontecer no `arm-jitter`; quando parar por acesso de hardware ausente, a correcao deve acontecer no `gbaemu`.

## Proximos passos sugeridos

1. Rodar a BIOS real ate a primeira instrucao/acesso nao suportado e registrar o ponto de parada.
2. Corrigir o proximo ponto de BIOS real apos o display ligar: fluxo de IRQ/retorno para continuar a animacao do boot.
3. Agendar DMA Special para audio/cartucho e conectar os FIFOs de audio aos timers.
4. Expandir a PPU para windows, mosaic e blending.
5. Depois que a BIOS completar, criar skip BIOS com estado inicial equivalente.
