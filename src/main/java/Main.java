//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.

import com.google.api.client.auth.oauth2.*;
import com.google.api.client.googleapis.auth.oauth2.*;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.File;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;

public class Main {
    public static void main(String[] args) throws Exception{
        Credential credential = oauth2(HTTP_TRANSPORT, args[0], args[1]);
    }

    private static NetHttpTransport HTTP_TRANSPORT = new NetHttpTransport();
    private static GsonFactory JSON_FACTORY = new GsonFactory();

    private static Credential oauth2(final NetHttpTransport transport, String clientId, String clientSecret) throws IOException {

        final AuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(transport,
                JSON_FACTORY,
                clientId,
                clientSecret,
                Arrays.asList(DriveScopes.DRIVE))
                .setAccessType("offline")
                .build();

        HttpServer httpServer = HttpServer.create(new InetSocketAddress(8888), 0);
        httpServer.createContext("/callback", exchange -> {
            try {

                String query = exchange.getRequestURI().getQuery();
                String method = exchange.getRequestMethod();

                System.out.println("query: " + query);
                System.out.println("method: " + method);
                String code = query.split("[=&]")[1];
                System.out.println("code=" + code);

                exchange.sendResponseHeaders(200, 0);
                exchange.close();

                Credential c = tokenRequest(flow, code);

                Drive driveService = new Drive.Builder(HTTP_TRANSPORT, JSON_FACTORY, c)
                        .setApplicationName("your-application-name")
                        .build();

                System.out.println("xxxxxxxxxxxxxxxx");
                FileList result = driveService.files().list()
                        .setPageSize(10)
                        .setFields("nextPageToken, files(id, name)")
                        .execute();
                List<File> files = result.getFiles();
                System.out.println(files);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        httpServer.start();


        AuthorizationCodeRequestUrl authorizationUrl = flow.newAuthorizationUrl();
        authorizationUrl.setRedirectUri("http://localhost:8888/callback");
        System.out.printf("Please, authorize application at: %s\n", authorizationUrl);

        return null;
    }

    private static Credential tokenRequest(AuthorizationCodeFlow flow, String code) throws IOException {
        AuthorizationCodeTokenRequest tokenRequest = flow.newTokenRequest(code);
        tokenRequest.setRedirectUri("http://localhost:8888/callback");
        TokenResponse tokenResponse = tokenRequest.execute();
        return flow.createAndStoreCredential(tokenResponse, "user");
    }
    
}