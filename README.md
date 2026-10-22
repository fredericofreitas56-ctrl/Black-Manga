# Black Manga

Aplicativo Android experimental para leitura web e tradução assistida de texto de mangás, com Kotlin e Jetpack Compose.

## Estado desta primeira versão

Implementado no código:
- Interface Android em tema escuro.
- Entrada manual de URLs HTTPS.
- Teste de resposta do site com timeout e indicação do código HTTP.
- Guardar fontes no dispositivo.
- Abrir sites num WebView integrado ou no navegador externo.
- Extrair o texto visível da página e enviá-lo ao ecrã de tradução.
- OCR no dispositivo para imagens escolhidas pelo utilizador (texto latino e japonês).
- Tradução offline com ML Kit depois de descarregar o modelo necessário.
- Workflow do GitHub Actions para compilar e publicar o APK debug como artefacto.

Limitações que ainda não devem ser confundidas com funcionalidades concluídas:
- Um teste HTTP não comprova licença, compatibilidade de catálogo ou permissão de distribuição.
- A importação automática de mangas/capítulos requer adaptadores específicos para fontes autorizadas; esses adaptadores ainda não foram implementados.
- A tradução do OCR é mostrada como texto separado. Ainda não apaga o texto original nem recompõe a imagem com a tradução.
- O ML Kit oferece a opção de idioma Português; não há seleção separada pt-BR na API. Revê expressões regionais quando necessário.
- Na primeira utilização, o modelo de tradução tem de ser baixado. Depois disso, a tradução pode funcionar offline enquanto o modelo estiver instalado.
- Conteúdo que só aparece após login, medidas anti-bot, DRM ou restrições do site não é contornado.

## Criar o APK gratuito

1. Abre a aba **Actions** deste repositório.
2. Escolhe **Build Black Manga APK**.
3. Clica em **Run workflow** (ou faz um push para a branch main).
4. Abre a execução concluída.
5. Na secção **Artifacts**, baixa **black-manga-debug-apk**.
6. Extrai o ZIP e copia **app-debug.apk** para o Android.
7. Autoriza a instalação de apps dessa origem, se o Android pedir, e instala.

O APK debug é para testes, não é uma versão assinada para publicação na Play Store. O GitHub Actions compila em nuvem, por isso o computador local não precisa de compilar o projeto.

## Ferramentas e dependências

- Android Gradle Plugin 8.13.2 e Gradle 8.13
- Kotlin 2.2.20
- Jetpack Compose
- ML Kit Translation
- ML Kit Text Recognition (latino e japonês)

## Aviso sobre fontes

Só adiciona fontes que tenhas autorização para consultar. A capacidade de abrir uma URL não significa que o site permita extração automatizada ou distribuição de capítulos. A próxima fase deve implementar adaptadores específicos a fontes com termos/API compatíveis.
