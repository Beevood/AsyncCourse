import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class Main {
    private final static HttpClient client = HttpClient.newHttpClient();
    private final static ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final static HttpResponse.BodyHandler<String> BODY_HANDLER = HttpResponse.BodyHandlers.ofString();

    public static void main(String[] args) {
        long time = System.currentTimeMillis();
        User user = getUser();
        System.out.println(user);
        System.out.println(System.currentTimeMillis() - time);
    }

    public record User(String name, String email, List<Repo> repos) {
        public User(@JsonProperty("login") String name, String email, List<Repo> repos) {
            this.name = name;
            this.email = email;
            this.repos = repos == null ? new ArrayList<>() : repos;
        }

        public void addAllRepos(List<Repo> repos) {
            this.repos.addAll(repos);
        }
    }

    public record Repo(String name, String contributeUrl, List<String> contributeNames) {
        public Repo(String name, @JsonProperty("contributors_url") String contributeUrl, List<String> contributeNames) {
            this.name = name;
            this.contributeUrl = contributeUrl;
            this.contributeNames = contributeNames == null ? new ArrayList<>() : contributeNames;
        }

        public void addContributeNames(List<String> ctr) {
            this.contributeNames.addAll(ctr);
        }
    }

    record Contributor(@JsonProperty("login") String name) {
    }

    public static User getUser() {
        HttpRequest requestClient = httpRequestGET("https://api.github.com/users/pivotal");
        HttpRequest requestRepo = httpRequestGET("https://api.github.com/users/pivotal/repos");

        CompletableFuture<List<Repo>> reposFuture = client.sendAsync(requestRepo, BODY_HANDLER)
                .thenApply(HttpResponse::body)
                .thenApply(Main::parseRepos)
                .thenApply(repos -> {
                            List<CompletableFuture<Void>> allFutures = new ArrayList<>();
                            List<Repo> resultRepos = repos.stream().limit(10).toList();
                            resultRepos.forEach(repo ->
                                    allFutures.add(client.sendAsync(httpRequestGET(repo.contributeUrl), BODY_HANDLER)
                                            .thenApply(HttpResponse::body)
                                            .thenApply(Main::parseContributors)
                                            .thenAccept(repo::addContributeNames)
                                            .thenAccept(nothing -> System.out.println("contribute"))
                                    ));
                            CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[0])).join();
                            return resultRepos;
                        }
                );

        return client.sendAsync(requestClient, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenApply(Main::parseUser)
                .thenCombine(reposFuture, (userF, reposF) -> {
                    userF.addAllRepos(reposF);
                    return userF;
                })
                .thenApply(f -> {
                    System.out.println("user");
                    return f;
                }).join();
    }

    private static HttpRequest httpRequestGET(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
    }

    private static List<Repo> parseRepos(String jsonString) {
        try {
            return Arrays.stream(mapper.readValue(jsonString, Repo[].class)).toList();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<String> parseContributors(String jsonString) {
        try {
            return Arrays.stream(mapper.readValue(jsonString, Contributor[].class)).map(Contributor::name).toList();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }


    private static User parseUser(String jsonString) {
        try {
            return mapper.readValue(jsonString, User.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

}
