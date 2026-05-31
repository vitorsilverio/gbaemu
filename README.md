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
- Boot por ROM direto ou por BIOS+ROM, com PC inicial em `00000000` no caminho com BIOS.
- Renderer inicial `GbaVideo` com framebuffer ARGB de 240x160.
- DMA imediato inicial para DMA0-3, cobrindo halfword/word, incremento/decremento/fixo/reload e mascaras de endereco.
- Interrupcoes iniciais com IE/IF/IME, write-one-to-clear em IF, linha externa da CPU e pedidos de LCD/DMA.
- Timers TM0-3 com reload, prescaler, cascata e IRQ-on-overflow.
- Keypad com `KEYINPUT` active-low, `KEYCNT` OR/AND e IRQ de keypad.
- Cartucho com parsing do header GBA, titulo, game code, maker code, fixed value e complement check.
- PPU implementada ate agora:
  - Modos bitmap 3, 4 e 5 com page select nos modos 4/5.
  - Backgrounds regulares/text BG em modo 0 e BG0/BG1 em modo 1.
  - Scroll `BGxHOFS/BGxVOFS`, tilemaps 4bpp/8bpp, screen sizes 256/512, flip H/V e prioridade basica.
  - Backgrounds affine BG2/BG3 em modos 1/2 com matriz, referencia, wrap e prioridade.
  - OBJ/sprites regulares 4bpp/8bpp, shape/size, flip H/V, prioridade contra BG e mapeamento 1D/2D.
  - Timing inicial de LCD com `VCOUNT`, flags de `DISPSTAT`, VBlank e HBlank.
  - Ainda faltam affine OBJ, mosaic, windows, alpha blending/brightness e timing com IRQ por scanline.
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

Criar uma instancia programatica:

```java
byte[] bios = Files.readAllBytes(Path.of("gba_bios.bin"));
byte[] rom = Files.readAllBytes(Path.of("game.gba"));
GbaConsole console = GbaConsole.fromBiosAndRom(bios, rom);
console.stepCpu(1);
int[] argb = console.renderFrame();
```

Tambem existe `GbaConsole.fromRom(rom)` para o caminho futuro de skip BIOS, iniciando em `08000000`.

## Regras de desenvolvimento

- Sempre criar ou atualizar testes automatizados junto com comportamento novo.
- Manter o README atualizado quando o uso, arquitetura ou escopo mudar.
- Nao executar comandos fora do sandbox; se algum passo exigir acesso externo ou permissao especial, o comando deve ser pedido ao usuario.
- Preferir a API publica do `arm-jitter` para CPU/JIT e isolar detalhes especificos do GBA no `gbaemu`.
- Usar o GBATEK como fonte para memoria, I/O, DMA, timers, PPU, audio, keypad, interrupcoes, BIOS e cartuchos.
- A BIOS real deve ser tratada como programa de teste de integracao: quando ela parar por instrucao ARM ainda nao implementada, a correcao deve acontecer no `arm-jitter`; quando parar por acesso de hardware ausente, a correcao deve acontecer no `gbaemu`.

## Proximos passos sugeridos

1. Rodar a BIOS real ate a primeira instrucao/acesso nao suportado e registrar o ponto de parada.
2. Implementar registradores basicos de I/O alem de `DISPCNT`, principalmente `VCOUNT`, `DISPSTAT` e interrupcoes.
3. Agendar DMA em HBlank/VBlank/Special e integrar IRQs.
4. Expandir a PPU para affine OBJ, windows e blending.
5. Depois que a BIOS completar, criar skip BIOS com estado inicial equivalente.
