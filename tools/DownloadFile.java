import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class DownloadFile {
  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      System.err.println("Usage: DownloadFile <url> <dest-file>");
      System.exit(2);
    }

    URI uri = URI.create(args[0]);
    Path dest = Path.of(args[1]).toAbsolutePath().normalize();
    Path parent = dest.getParent();
    if (parent != null) Files.createDirectories(parent);

    HttpClient client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMinutes(30))
            .header("User-Agent", "TrecMusicDownloader/1.0")
            .header("Accept-Encoding", "identity")
            .GET()
            .build();

    Path tmp = dest.resolveSibling(dest.getFileName().toString() + ".download");
    try {
      Files.deleteIfExists(tmp);
      HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
      int code = response.statusCode();
      if (code < 200 || code >= 300) {
        Files.deleteIfExists(tmp);
        throw new RuntimeException("HTTP " + code);
      }
      Files.deleteIfExists(dest);
      Files.move(tmp, dest);
    } finally {
      Files.deleteIfExists(tmp);
    }

    System.out.println("Downloaded to " + dest);
    System.out.println("Size bytes: " + Files.size(dest));
  }
}

