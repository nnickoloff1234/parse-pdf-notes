//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.

import com.google.api.client.auth.oauth2.*;
import com.google.api.client.googleapis.auth.oauth2.*;
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest;
import com.google.api.client.googleapis.services.GoogleClientRequestInitializer;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveRequestInitializer;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.File;
import com.sun.net.httpserver.HttpServer;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

public class Main {
    private static AuthorizationCodeRequestUrl url;
    private static String redirectUrl;
    private static List<File> files;
    private static Map<String, File> filesToDownload;
    private static Set<String> downloadedFiles = new HashSet<>();

    private static String callbackUrlPath = "/callback";
    private static String startUrlPath = "/start";
    private static String syncFilesPathUrl = "/syncFiles";
    private static String parseNotesPathUrl = "/parseNotes";
    private static String syncToWikiPathUrl = "/syncToWiki";
    private static String statusDowloadPathUrl = "/statusDowload";
    private static String statusSyncToWikiPathUrl = "/statusSyncToWiki";

    private static String downloadsDirName = "downloads";

    private static String rootFolder;
    private static String workDir;
    private static Drive driveService;

    private static Semaphore s = new Semaphore(0);

    public static void main(String[] args) throws Exception {

        String clientId = args[0];
        String clientSecret = args[1];
        String proto = args[2];
        String fqdn = args[3];
        String port = args[4];

        rootFolder = args[5];
        workDir = args[6];
        redirectUrl = "%s://%s:%s%s".formatted(proto, fqdn, port, callbackUrlPath);

        doDownloading();

        url = oauth2Url(HTTP_TRANSPORT, clientId, clientSecret);
    }

//    private DriveRequestInitializer setHttpTimeout(final HttpRequestInitializer requestInitializer) {
//        return new HttpRequestInitializer() {
//            @Override
//            public void initialize(HttpRequest httpRequest) throws IOException {
//                requestInitializer.initialize(httpRequest);
//                httpRequest.setConnectTimeout(3 * 60000);  // 3 minutes connect timeout
//                httpRequest.setReadTimeout(3 * 60000);  // 3 minutes read timeout
//            }
//        };
//    }

    private static void doDownloading() {
        Thread t = new Thread(() -> {
            try {
                java.io.File downloadsDirFile = new java.io.File(workDir, downloadsDirName);
                downloadsDirFile.mkdirs();

                System.out.println("Started background downloader thread...");
                s.acquire();
                System.out.println("Acquired permit to download...");
                for (Map.Entry<String, File> file : filesToDownload.entrySet()) {
                    try (FileOutputStream fos = new FileOutputStream(new java.io.File(downloadsDirFile, file.getKey()))) {
                        System.out.println(Thread.currentThread().getName() + "Downloading " + file.getValue().getName() + " to " + file.getKey());
                        driveService.files().get(file.getValue().getId()).executeMediaAndDownloadTo(fos);
                        downloadedFiles.add(file.getKey());
                    }
                }
                System.out.println("Download finished.");
            } catch (Exception e) {e.printStackTrace();}
        });
        t.start();
    }

    public class DisableTimeout implements HttpRequestInitializer { public void initialize(HttpRequest request) { request.setConnectTimeout(0); request.setReadTimeout(0); } }

    private static NetHttpTransport HTTP_TRANSPORT = new NetHttpTransport();
    private static GsonFactory JSON_FACTORY = new GsonFactory();

    private static void startServer(final AuthorizationCodeFlow flow) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(8888), 0);
        httpServer.createContext(callbackUrlPath, exchange -> {
            try {
                System.out.println("callbackUrlPath");
                String query = exchange.getRequestURI().getQuery();
                String method = exchange.getRequestMethod();

                System.out.println("query: " + query);
                System.out.println("method: " + method);
                String code = query.split("[=&]")[1];
                System.out.println("code=" + code);

                Credential c = tokenRequest(flow, code);

                driveService = new Drive.Builder(HTTP_TRANSPORT, JSON_FACTORY, c)
                        .setApplicationName("your-application-name")
                        .setDriveRequestInitializer(new DriveRequestInitializer() {
                            public void initialize(HttpRequest request) throws IOException {
                                request.setReadTimeout(3 * 60000);
                                request.setConnectTimeout(30 * 60000);
                            }
                        } )
                        .setHttpRequestInitializer(new HttpRequestInitializer() {
                            @Override
                            public void initialize(HttpRequest httpRequest) throws IOException {
                                c.initialize(httpRequest);
                                System.out.println("getConnectTimeout >>> "+httpRequest.getConnectTimeout());
                                System.out.println("getReadTimeout >>> "+httpRequest.getReadTimeout());
                                httpRequest.setConnectTimeout(0);
                                httpRequest.setReadTimeout(0);
                            }
                        })
//                        .setGoogleClientRequestInitializer(new GoogleClientRequestInitializer() {
//                            @Override
//                            public void initialize(AbstractGoogleClientRequest<?> abstractGoogleClientRequest) throws IOException {
//
//                            }
//                        })
                        .build();

                System.out.println("xxxxxxxxxxxxxxxx");
                HashMap<String, File> idToFile = new HashMap<>();
                String nextPageToken = "";
                while (nextPageToken != null) {
                    FileList result = driveService.files().list()
                            .setQ("trashed = false")
                            .setPageToken(nextPageToken)
                            .setPageSize(10)
//                            .setFields("nextPageToken, files(id, name)")
                            .setFields("nextPageToken, files(*)")
//                            .setOrderBy("name")
                            .execute();
                    nextPageToken = result.getNextPageToken();
                    List<File> filesList = result.getFiles();
                    filesList.forEach((f) -> { idToFile.put(f.getId(), f);});
//                    System.out.println(filesList);
                    if (files == null) {
                        files = filesList;
                    } else {
                        files.addAll(filesList);
                    }
                }
                System.out.println("Total files: " + files.size());
                filesToDownload = new HashMap<>();
                for (File file : files) {
                    String fullPath = hasAncestor(file, rootFolder, idToFile);
                    if (fullPath != null && (file.getMimeType() == null || !file.getMimeType().equals("application/vnd.google-apps.folder"))) {
                        File parent = idToFile.get(file.getParents().get(0));
                        System.out.println(fullPath + " cp:" + file.getCopyRequiresWriterPermission() + " " + file.getIsAppAuthorized() + " " + file.getMimeType() + " " + file.getWebViewLink() + " " + file.getExportLinks() + " " + file.getWebContentLink() + " " + file.getThumbnailLink() + file.getIconLink() + parent.getWebViewLink());
                        filesToDownload.put(fullPath, file);
                    }
                }
                System.out.println("Files to download: " + filesToDownload.size());
                String responseBody = """
                        <html>
                            <body>
                                <a href="%s">Download new/modified files</a>
                                %s
                            </body>
                        </html>
                        """.formatted(syncFilesPathUrl, filesToDownload.entrySet().stream().map(f-> "<div>"+f.getKey()+"</div>").collect(Collectors.joining()));
                exchange.sendResponseHeaders(200, responseBody.getBytes(StandardCharsets.UTF_8).length);
                PrintWriter pw = new PrintWriter(exchange.getResponseBody());
                pw.write(responseBody);
                pw.flush();
                exchange.getResponseBody().close();



//                System.out.println(files);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        /// //////////////////////////////////////////////////////////////
        httpServer.createContext(startUrlPath, exchange -> {
            try {

                System.out.println("startUrlPath");
                String responseBody = """
                        <html>
                            <body>
                                <a href="%s">callback</a>
                            </body>
                        </html>
                        """.formatted(url);
                exchange.sendResponseHeaders(200, responseBody.length());
                PrintWriter pw = new PrintWriter(exchange.getResponseBody());
                pw.write(responseBody);
                pw.flush();
                exchange.getResponseBody().close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        /// ////////////////////////////////////////////////////
        httpServer.createContext(syncFilesPathUrl, exchange -> {
            try {
                System.out.println("syncFilesPathUrl");

                String responseBody = """
                        <html>
                            <body>
                                <a href="%s">see status</a>
                            </body>
                        </html>
                        """.formatted(statusDowloadPathUrl);
                exchange.sendResponseHeaders(200, responseBody.length());
                PrintWriter pw = new PrintWriter(exchange.getResponseBody());
                pw.write(responseBody);
                pw.flush();
                exchange.getResponseBody().close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            s.release(1);
        });
        httpServer.createContext(statusDowloadPathUrl, exchange -> {
            System.out.println("statusDowloadPathUrl");
            String responseBody = """
                        <html>
                            <body>
                                <a href="%s">sync to wiki</a>
                                %s
                            </body>
                        </html>
                        """.formatted(syncToWikiPathUrl, filesToDownload.entrySet().stream().map(e -> {return "<div>"+e.getKey()+" "+(downloadedFiles.contains(e.getKey())?"<b>OK</b>":"")+ "</div>";}).collect(Collectors.joining()));
            exchange.sendResponseHeaders(200, responseBody.length());
            PrintWriter pw = new PrintWriter(exchange.getResponseBody());
            pw.write(responseBody);
            pw.flush();
            exchange.getResponseBody().close();

        });
        httpServer.start();
    }

    private static AuthorizationCodeRequestUrl oauth2Url(final NetHttpTransport transport, String clientId, String clientSecret) throws IOException {

        final AuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(transport,
                JSON_FACTORY,
                clientId,
                clientSecret,
                Arrays.asList(DriveScopes.DRIVE))
                .setAccessType("offline")
                .build();

        startServer(flow);

        AuthorizationCodeRequestUrl authorizationUrl = flow.newAuthorizationUrl();
        authorizationUrl.setRedirectUri(redirectUrl);
        System.out.printf("Please, authorize application at: %s\n", authorizationUrl);

        return authorizationUrl;
    }

    private static Credential tokenRequest(AuthorizationCodeFlow flow, String code) throws IOException {
        AuthorizationCodeTokenRequest tokenRequest = flow.newTokenRequest(code);
        tokenRequest.setRedirectUri(redirectUrl);
        TokenResponse tokenResponse = tokenRequest.execute();
        return flow.createAndStoreCredential(tokenResponse, "user");
    }

    private static String hasAncestor(File file, String ancestorName, HashMap<String, File> idToFile) {
        File parent = file.getParents() == null? null: idToFile.get(file.getParents().get(0));//TODO: how could they even have more than one parents?
        String fullPath = parent == null? "ROOT/" + file.getName(): parent.getName() + "/" + file.getName();
        while (parent != null && !parent.getName().equals(ancestorName)) {
            parent = parent.getParents() == null? null: idToFile.get(parent.getParents().get(0));
            fullPath = parent == null? "ROOT/" + fullPath: parent.getName() + "/" + fullPath;
        }
        return parent != null? fullPath.replace(ancestorName+"/", "").replaceAll("([^\\w]|[ ])+", "_"): null;
    }

}