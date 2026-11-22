# NotePin → ChatGPT: prototyp Android

**Cel:** wykorzystać NotePin PN0200 do rozmowy z modelem językowym bez serwera na komputerze.

**Status:** eksperymentalny wariant tekstowy; nie jest jeszcze mikrofonem czasu rzeczywistego ani integracją z historią rozmów konta ChatGPT.

## Co zostało zaimplementowane

- Baza: [NotePin Live Audio](https://github.com/hrvtuirvw/notepin-live-audio), licencja MIT, przypięta do commita `87cc25eb`.
- Dodatkowa próba połączenia GATT z obiektem `BluetoothDevice` zwróconym przez skan BLE. Rozwiązanie inspirowane działającą poprawką Omi dla PN0200.
- Bez modyfikowania firmware, rozpoczynania, kończenia ani usuwania nagrań.
- Odczyt WAV pobranych przez istniejącą usługę aplikacji.
- Ręczna transkrypcja wybranego WAV przez `gpt-4o-mini-transcribe`.
- Udostępnienie transkrypcji jako tekstu przez systemowy Android Share Sheet (można wybrać ChatGPT, jeśli aplikacja występuje jako odbiorca).
- Opcjonalne jednorazowe pytanie do `gpt-4o-mini` przez Responses API. Odpowiedź pojawia się wyłącznie w tej aplikacji.
- Klucz OpenAI API wprowadzany **na telefonie**, zaszyfrowany AES-GCM z kluczem Android Keystore.
- Potwierdzenie przed **każdym** wysłaniem WAV lub tekstu do OpenAI.
- Żadne hasło, cookie, klucz PLAUD, klucz API ani nagranie nie są zawarte w repozytorium czy GitHub Actions.

## Instalacja na telefonie

1. Otwórz ostatni udany przebieg [GitHub Actions](https://github.com/MatkaBoska/MatkaBoska/actions/workflows/notepin-chatgpt-bridge.yml?query=branch%3Anotepin-chatgpt-bridge-20261122).
2. W `Artifacts` pobierz `PN0200-ChatGPT-bridge-debug-APK`. Rozpakuj APK.
3. Zainstaluj aplikację **NotePin Live Audio**. To osobna aplikacja od Omi Dev.
4. Przed pobieraniem nagrań zaimportuj `private-android-bootstrap.json` wygenerowany na podstawie **własnego** konta PLAUD. Instrukcja autora: https://github.com/hrvtuirvw/notepin-live-audio/blob/main/docs/AUTHENTICATION.md .
5. Uruchom pobieranie nagrań (oryginalny ekran ma część przycisków po japońsku). Gdy zostanie pobrany pierwszy WAV, wybierz `ChatGPT: transkrypcja WAV i pytania`.
6. Wpisz własny klucz OpenAI API, wybierz WAV i transkrybuj; ewentualnie wyślij tekst do aplikacji ChatGPT przez udostępnianie albo zapytaj model przez API.

**UWAGA:** bootstrap JSON zawiera prywatny klucz RSA. Nie wklejaj go do rozmowy, nie dodawaj do repozytorium, nie uruchamiaj generowania na publicznym runnerze GitHub.

## Ograniczenia

- Autor upstream potwierdził działanie z oryginalnym NotePin **model 880**. PN0200 jest na razie niezweryfikowany z tym protokołem, mimo działającego GATT.
- Własny plik bootstrap zwykle wymaga zalogowania się do oficjalnego PLAUD na komputerze. Nie próbujemy pozyskiwać kluczy bez autoryzacji właściciela.
- W obecnej wersji WAV powstają w fragmentach do pięciu minut, a krótszy jest finalizowany przy zatrzymaniu pobierania lub ponownym połączeniu. Brak gwarancji małej latencji.
- Subskrypcja ChatGPT i rozliczenie API to osobne usługi; model transkrypcji i odpowiedź AI mogą generować opłaty.
- Systemowe udostępnienie tekstu nie gwarantuje, że zainstalowany ChatGPT zaoferuje odpowiedni cel; w razie potrzeby należy skopiować tekst i wkleić go do rozmowy.
- Całość jest prototypem podpisanym debugowo. Nie włączaj automatycznego przesyłania prywatnych nagrań.

## Kod i budowanie

- `notepin/chatgpt/ChatBridgeActivity.java`: ekran użytkownika, API, przechowywanie klucza.
- `notepin/chatgpt/apply_bridge.py`: ostrożna modyfikacja źródeł upstream podczas CI.
- `.github/workflows/notepin-chatgpt-bridge.yml`: kompilacja APK.

Następny etap po potwierdzeniu uwierzytelnienia PN0200 i pobrania PCM: krótkie, jawnie zatwierdzane fragmenty transkrypcji z `LiveStream.Capture`, a dopiero potem interaktywna komunikacja o małej latencji.
