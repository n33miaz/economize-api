"""Normaliza o /v3/api-docs para virar arquivo versionado.

Por que normalizar em vez de gravar o que o springdoc devolve:

  * `servers[0].url` e GERADO a partir de onde a aplicacao esta rodando. Em
    producao vem `https://economize-api.onrender.com`, na esteira vem
    `http://localhost:8080` e na minha maquina veio `http://localhost:8097`.
    Sem fixar isso, o arquivo versionado nunca bateria com o gerado e a
    verificacao viveria falhando por um motivo que nao e o contrato.

  * A ordem das chaves de um mapa JSON nao e garantida entre versoes da
    biblioteca. `sort_keys` torna o diff legivel: quando a esteira acusar
    diferenca, o que aparece e a rota que mudou, nao o arquivo inteiro
    embaralhado.

Uso, igual na esteira e na maquina de quem desenvolve:

    curl -sf http://localhost:8080/v3/api-docs | python3 scripts/normalizar-openapi.py > docs/openapi.json
"""

import json
import sys

SERVIDOR = [
    {
        "url": "https://economize-api.onrender.com",
        "description": "producao",
    }
]


def main() -> None:
    documento = json.load(sys.stdin)
    documento["servers"] = SERVIDOR
    json.dump(documento, sys.stdout, ensure_ascii=False, indent=2, sort_keys=True)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
