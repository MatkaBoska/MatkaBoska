package dev.notepin.poc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Explicit opt-in OpenAI bridge for WAV files already recovered by CollectorService.
 * It never reads PLAUD owner credentials or sends audio automatically.
 * ChatGPT app sharing and OpenAI API calls are two separate user-selected paths.
 */
public final class ChatBridgeActivity extends Activity {
  private static final String KEY_ALIAS = "notepin-openai-api-key";
  private static final String KEY_PREF = "encrypted_api_key_v1";
  private static final int MAX_WAV_BYTES = 24 * 1024 * 1024;
  private static final MediaType WAV = MediaType.parse("audio/wav");
  private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final OkHttpClient http = new OkHttpClient.Builder()
      .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
      .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
      .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build();
  private TextView status;
  private TextView transcriptView;
  private EditText prompt;
  private File selected;
  private String transcript = "";
  private boolean busy;

  private LinearLayout layout() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setPadding(32, 32, 32, 32);
    return l;
  }

  private Button button(LinearLayout l, String label, View.OnClickListener callback) {
    Button b = new Button(this);
    b.setText(label);
    l.addView(b);
    b.setOnClickListener(callback);
    return b;
  }

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    LinearLayout root = layout();
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.addView(root);
    setContentView(scroll);
    TextView title = new TextView(this);
    title.setTextSize(23);
    title.setText("NotePin → ChatGPT (test)");
    root.addView(title);
    TextView explanation = new TextView(this);
    explanation.setText("Nagrania są pobierane przez NotePin Live Audio. Transkrypcja i odpowiedź AI są płatnymi wywołaniami OpenAI API, osobno od subskrypcji ChatGPT. Nic nie jest wysyłane bez potwierdzenia. To nie jest mikrofon na żywo.");
    root.addView(explanation);
    button(root, "1. Ustaw klucz OpenAI API", v -> setApiKey());
    button(root, "Usuń klucz API z telefonu", v -> {
      getSharedPreferences("bridge.secrets", MODE_PRIVATE).edit().remove(KEY_PREF).apply();
      Toast.makeText(this, "Klucz usunięty z ustawień aplikacji", Toast.LENGTH_LONG).show();
    });
    button(root, "2. Wybierz pobrany plik WAV", v -> pickWav());
    button(root, "3. Transkrybuj wybrany WAV (OpenAI API)", v -> confirmTranscription());
    status = new TextView(this);
    status.setText("Wybierz nagranie WAV. Klucz API wpisujesz tylko na telefonie.");
    root.addView(status);
    transcriptView = new TextView(this);
    transcriptView.setTextIsSelectable(true);
    transcriptView.setTextSize(16);
    root.addView(transcriptView);
    button(root, "Kopiuj transkrypcję", v -> {
      if (transcript.isEmpty()) { show("Brak transkrypcji."); return; }
      ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
      manager.setPrimaryClip(ClipData.newPlainText("NotePin transcript", transcript));
      show("Skopiowano.");
    });
    button(root, "4A. Udostępnij tekst do ChatGPT", v -> shareText());
    TextView questionLabel = new TextView(this);
    questionLabel.setText("Pytanie do modelu API (opcjonalnie):");
    root.addView(questionLabel);
    prompt = new EditText(this);
    prompt.setMinLines(2);
    prompt.setText("Odpowiedz na pytanie zawarte w nagraniu. Jeśli nie ma pytania, podsumuj najważniejsze informacje.");
    root.addView(prompt);
    button(root, "4B. Odpowiedz w aplikacji przez OpenAI API", v -> answer());
  }

  private void show(String text) {
    if (status != null) status.setText(text);
  }

  private static SecretKey encryptionKey() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (!store.containsAlias(KEY_ALIAS)) {
      KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
          KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
      generator.generateKey();
    }
    return (SecretKey) store.getKey(KEY_ALIAS, null);
  }

  private void storeKey(String key) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, encryptionKey());
    byte[] iv = cipher.getIV();
    byte[] cipherText = cipher.doFinal(key.getBytes(StandardCharsets.UTF_8));
    byte[] all = new byte[iv.length + cipherText.length];
    System.arraycopy(iv, 0, all, 0, iv.length);
    System.arraycopy(cipherText, 0, all, iv.length, cipherText.length);
    if (!getSharedPreferences("bridge.secrets", MODE_PRIVATE).edit()
        .putString(KEY_PREF, Base64.encodeToString(all, Base64.NO_WRAP)).commit()) {
      throw new IllegalStateException("Cannot save key");
    }
  }

  private String loadKey() throws Exception {
    String b64 = getSharedPreferences("bridge.secrets", MODE_PRIVATE).getString(KEY_PREF, "");
    if (b64.isEmpty()) throw new IllegalStateException("Ustaw najpierw klucz API.");
    byte[] all = Base64.decode(b64, Base64.NO_WRAP);
    if (all.length < 28) throw new IllegalStateException("Uszkodzony zapis klucza.");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, Arrays.copyOf(all, 12)));
    return new String(cipher.doFinal(Arrays.copyOfRange(all, 12, all.length)), StandardCharsets.UTF_8);
  }

  private void setApiKey() {
    EditText edit = new EditText(this);
    edit.setHint("sk-...");
    edit.setSingleLine(true);
    edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    new AlertDialog.Builder(this).setTitle("Klucz OpenAI API")
      .setMessage("Wpisz swój klucz bezpośrednio tutaj. Jest szyfrowany w Android Keystore i nie trafia do GitHuba.")
      .setView(edit).setNegativeButton("Anuluj", null)
      .setPositiveButton("Zapisz", (d, w) -> {
        String key = edit.getText().toString().trim();
        if (!key.startsWith("sk-") || key.length() < 20) {
          show("Klucz ma niepoprawny format.");
          return;
        }
        try { storeKey(key); show("Klucz API zapisany lokalnie."); }
        catch (Exception e) { show("Nie można zapisać klucza."); }
      }).show();
  }

  private File[] files() {
    File[] files = new File(getFilesDir(), "recordings").listFiles(
        (directory, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".wav"));
    if (files == null) return new File[0];
    Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
    return files;
  }

  private void pickWav() {
    File[] wavs = files();
    if (wavs.length == 0) { show("Brak WAV. Najpierw pobierz nagranie w głównym ekranie."); return; }
    int n = Math.min(wavs.length, 30);
    String[] labels = new String[n];
    for (int i = 0; i < n; i++) {
      labels[i] = wavs[i].getName() + " (" + (wavs[i].length() / 1024) + " KiB)";
    }
    new AlertDialog.Builder(this).setTitle("Wybierz WAV")
        .setItems(labels, (dialog, index) -> {
          selected = wavs[index];
          transcript = "";
          transcriptView.setText("");
          show("Wybrano: " + selected.getName());
        }).setNegativeButton("Anuluj", null).show();
  }

  private void confirmTranscription() {
    if (busy) return;
    if (selected == null || !selected.isFile()) { show("Najpierw wybierz WAV."); return; }
    if (selected.length() <= 44 || selected.length() > MAX_WAV_BYTES) {
      show("WAV musi mieć od 45 bajtów do 24 MiB.");
      return;
    }
    new AlertDialog.Builder(this).setTitle("Wysłać nagranie do OpenAI?")
      .setMessage("Wybrany plik: " + selected.getName() +
        "\nRozmiar: " + (selected.length() / 1024) + " KiB" +
        "\nTranskrypcja jest płatna przez OpenAI API. Dźwięk opuści telefon tylko po zatwierdzeniu.")
      .setNegativeButton("Anuluj", null)
      .setPositiveButton("Transkrybuj", (d, w) -> transcribe()).show();
  }

  private void transcribe() {
    if (busy) return;
    busy = true; show("Wysyłam WAV do OpenAI API, czekam na tekst…");
    final File file = selected;
    io.execute(() -> {
      try {
        String key = loadKey();
        RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "gpt-4o-mini-transcribe")
            .addFormDataPart("file", file.getName(), RequestBody.create(file, WAV))
            .build();
        Request request = new Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .header("Authorization", "Bearer " + key).post(body).build();
        try (Response response = http.newCall(request).execute()) {
          if (!response.isSuccessful()) throw new IllegalStateException("API HTTP " + response.code());
          if (response.body() == null) throw new IllegalStateException("Pusta odpowiedź serwera");
          String value = new JSONObject(response.body().string()).optString("text", "");
          if (value.isBlank()) throw new IllegalStateException("Brak tekstu w odpowiedzi");
          runOnUiThread(() -> {
            transcript = value;
            transcriptView.setText(value);
            show("Transkrypcja gotowa. Teraz możesz udostępnić tekst lub zapytać model API.");
          });
        }
      } catch (Exception e) {
        runOnUiThread(() -> show("Transkrypcja nieudana: " + safeReason(e)));
      } finally { runOnUiThread(() -> busy = false); }
    });
  }

  private static String safeReason(Exception e) {
    if (e.getMessage() != null && e.getMessage().startsWith("API HTTP ")) return e.getMessage();
    if (e instanceof IllegalStateException) {
      String s = e.getMessage();
      if (s != null && (s.startsWith("Brak ") || s.startsWith("Pusta ") || s.startsWith("Ustaw ")))
        return s;
    }
    return e.getClass().getSimpleName(); // No credentials, prompts, or transcript in errors.
  }

  private void shareText() {
    if (transcript.isEmpty()) { show("Najpierw transkrybuj nagranie."); return; }
    Intent send = new Intent(Intent.ACTION_SEND);
    send.setType("text/plain");
    send.putExtra(Intent.EXTRA_TEXT, transcript);
    startActivity(Intent.createChooser(send, "Udostępnij tekst, np. do ChatGPT"));
  }

  private static String responseText(JSONObject response) {
    StringBuilder answer = new StringBuilder();
    JSONArray output = response.optJSONArray("output");
    if (output == null) return "";
    for (int i = 0; i < output.length(); i++) {
      JSONObject item = output.optJSONObject(i);
      if (item == null) continue;
      JSONArray content = item.optJSONArray("content");
      if (content == null) continue;
      for (int j = 0; j < content.length(); j++) {
        JSONObject part = content.optJSONObject(j);
        if (part != null && part.optString("type").equals("output_text")) {
          answer.append(part.optString("text"));
        }
      }
    }
    return answer.toString();
  }

  private void answer() {
    if (busy) return;
    if (transcript.isEmpty()) { show("Najpierw transkrybuj WAV."); return; }
    final String question = prompt.getText().toString().trim();
    final String speech = transcript;
    if (question.isEmpty()) { show("Wpisz pytanie."); return; }
    new AlertDialog.Builder(this).setTitle("Wysłać tekst do modelu OpenAI?")
      .setMessage("Transkrypcja i Twoje pytanie zostaną wysłane przez płatne API. Ta odpowiedź nie pojawi się automatycznie w historii konta ChatGPT.")
      .setNegativeButton("Anuluj", null)
      .setPositiveButton("Zapytaj", (d, w) -> {
        busy = true; show("Czekam na odpowiedź modelu…");
        io.execute(() -> {
          try {
            JSONObject input = new JSONObject()
                .put("model", "gpt-4o-mini")
                .put("input", new JSONArray().put(new JSONObject()
                    .put("role", "user")
                    .put("content", question + "\n\nTranskrypcja:\n" + speech)));
            Request request = new Request.Builder()
                .url("https://api.openai.com/v1/responses")
                .header("Authorization", "Bearer " + loadKey())
                .post(RequestBody.create(input.toString(), JSON)).build();
            try (Response response = http.newCall(request).execute()) {
              if (!response.isSuccessful()) throw new IllegalStateException("API HTTP " + response.code());
              if (response.body() == null) throw new IllegalStateException("Pusta odpowiedź serwera");
              String text = responseText(new JSONObject(response.body().string()));
              if (text.isEmpty()) throw new IllegalStateException("Brak odpowiedzi modelu");
              runOnUiThread(() -> new AlertDialog.Builder(this)
                  .setTitle("Odpowiedź AI").setMessage(text)
                  .setPositiveButton("OK", null)
                  .setNeutralButton("Kopiuj", (dialog, which) -> {
                    ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    manager.setPrimaryClip(ClipData.newPlainText("AI response", text));
                  }).show());
            }
          } catch (Exception e) {
            runOnUiThread(() -> show("Odpowiedź nieudana: " + safeReason(e)));
          } finally { runOnUiThread(() -> busy = false); }
        });
      }).show();
  }

  @Override protected void onDestroy() {
    io.shutdownNow();
    http.dispatcher().executorService().shutdown();
    http.connectionPool().evictAll();
    super.onDestroy();
  }
}
