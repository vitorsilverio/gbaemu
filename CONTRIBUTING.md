# Como contribuir

Este é um projeto pessoal, mas issues e pull requests são bem-vindos.

## Antes de abrir um PR

- Abra uma issue descrevendo o problema/ideia primeiro (use os templates de
  [bug](.github/ISSUE_TEMPLATE/bug.yml) ou [feature](.github/ISSUE_TEMPLATE/feature.yml)) —
  evita trabalho duplicado ou um PR que não se encaixa na direção do projeto.
- Compile e teste com **JBR 25** (a JDK do IntelliJ), não a JDK do sistema:

  ```bash
  mvn test
  ```

- Toda mudança de comportamento vem com teste automatizado cobrindo o caso novo.
- Use o [GBATEK](https://mgba-emu.github.io/gbatek/) como fonte de verdade para
  comportamento de hardware — o GBA é ARM7TDMI/ARMv4T, nunca aplique recursos ARMv5+.
- A CPU ARM/JIT vem do [`arm-jitter`](https://github.com/vitorsilverio/arm-jitter) — se
  sua mudança exigir uma feature nova da CPU em si, ela provavelmente pertence lá.
- Mantenha o estilo do código existente; não introduza dependências novas sem discutir
  antes na issue.

## Dúvidas

Abra uma issue ou veja a seção de contato no [README](README.md).
