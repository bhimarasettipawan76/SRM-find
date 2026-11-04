package com.srmfind;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Lightweight SRM Find Java web application backed by PostgreSQL. */
public class SRMFindApplication {
  private static String jdbcUrl, dbUser, dbPassword;
  public static void main(String[] args) throws Exception {
    String raw = System.getenv("DATABASE_URL");
    if (raw == null || raw.isBlank()) throw new IllegalStateException("DATABASE_URL environment variable is required");
    jdbcUrl = raw.startsWith("jdbc:") ? raw : "jdbc:postgresql://" + raw.replaceFirst("^postgres(ql)?://", "");
    dbUser = System.getenv("DB_USER"); dbPassword = System.getenv("DB_PASSWORD");
    if (dbUser == null || dbPassword == null) throw new IllegalStateException("DB_USER and DB_PASSWORD are required");
    try (Connection c = db(); Statement s = c.createStatement()) {
      s.executeUpdate("CREATE TABLE IF NOT EXISTS reports (id VARCHAR(30) PRIMARY KEY, type VARCHAR(8) NOT NULL, student_name TEXT NOT NULL, email TEXT NOT NULL, item_name TEXT NOT NULL, category TEXT NOT NULL, description TEXT NOT NULL, location TEXT NOT NULL, resolved BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW())");
      s.executeUpdate("CREATE SEQUENCE IF NOT EXISTS lost_report_seq START 1");
      s.executeUpdate("CREATE SEQUENCE IF NOT EXISTS found_report_seq START 1");
    }
    int port = Integer.parseInt(Optional.ofNullable(System.getenv("PORT")).orElse("8080"));
    HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
    server.createContext("/", SRMFindApplication::handle); server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(10)); server.start();
    System.out.println("SRM Find listening on port " + port);
  }
  static Connection db() throws SQLException { return DriverManager.getConnection(jdbcUrl, dbUser, dbPassword); }
  static void handle(HttpExchange ex) throws IOException {
    try {
      String path=ex.getRequestURI().getPath(), method=ex.getRequestMethod();
      if(path.equals("/health")){ send(ex,200,"text/plain","ok"); return; }
      if(path.equals("/") && method.equals("GET")){ try(InputStream in=SRMFindApplication.class.getResourceAsStream("/static/index.html")){ if(in==null){send(ex,500,"text/plain","Website file missing");return;} byte[] b=in.readAllBytes(); ex.getResponseHeaders().set("Content-Type","text/html; charset=utf-8"); ex.sendResponseHeaders(200,b.length); try(OutputStream o=ex.getResponseBody()){o.write(b);} } return; }
      if(path.equals("/api/reports") && method.equals("GET")){ String q=params(ex.getRequestURI().getRawQuery()).getOrDefault("q","").trim(); try(Connection c=db(); PreparedStatement p=c.prepareStatement("SELECT id,type,student_name,item_name,category,description,location FROM reports WHERE resolved=FALSE AND (?='' OR LOWER(item_name||' '||category||' '||description||' '||location||' '||id) LIKE ?) ORDER BY created_at DESC")){p.setString(1,q);p.setString(2,"%"+q.toLowerCase(Locale.ROOT)+"%");try(ResultSet r=p.executeQuery()){send(ex,200,"application/json",rows(r));}} return; }
      if(path.equals("/api/report") && method.equals("POST")){ Map<String,String> f=params(new String(ex.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)); String type=req(f,"type").toUpperCase(Locale.ROOT); if(!type.equals("LOST")&&!type.equals("FOUND"))throw new IllegalArgumentException("Choose LOST or FOUND."); String name=req(f,"studentName"),email=req(f,"email"),item=req(f,"itemName"),cat=req(f,"category"),desc=req(f,"description"),loc=req(f,"location"); if(!email.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"))throw new IllegalArgumentException("Enter a valid email address format.");
        try(Connection c=db()){ c.setAutoCommit(false); String seq=type.equals("LOST")?"lost_report_seq":"found_report_seq"; long n; try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT nextval('"+seq+"')")){r.next();n=r.getLong(1);} String id=type+"-"+String.format("%04d",n); try(PreparedStatement p=c.prepareStatement("INSERT INTO reports(id,type,student_name,email,item_name,category,description,location) VALUES(?,?,?,?,?,?,?,?)")){p.setString(1,id);p.setString(2,type);p.setString(3,name);p.setString(4,email);p.setString(5,item);p.setString(6,cat);p.setString(7,desc);p.setString(8,loc);p.executeUpdate();} String matches=matches(c,type,item,cat);c.commit();send(ex,201,"application/json","{\"message\":\"Report submitted\",\"id\":"+j(id)+",\"matches\":"+matches+"}"); } return; }
      if(path.equals("/api/resolve") && method.equals("POST")){ Map<String,String> f=params(new String(ex.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)); String lost=req(f,"lostId"),found=req(f,"foundId"),email=req(f,"email"); try(Connection c=db()){c.setAutoCommit(false);String sql="SELECT id,type,email,item_name,category,resolved FROM reports WHERE id IN (?,?) FOR UPDATE";Map<String,String[]> m=new HashMap<>();try(PreparedStatement p=c.prepareStatement(sql)){p.setString(1,lost);p.setString(2,found);try(ResultSet r=p.executeQuery()){while(r.next())m.put(r.getString(1),new String[]{r.getString(2),r.getString(3),r.getString(4),r.getString(5),Boolean.toString(r.getBoolean(6))});}}String[] l=m.get(lost),fnd=m.get(found);if(l==null||fnd==null)throw new IllegalArgumentException("Report ID not found.");if(!l[0].equals("LOST")||!fnd[0].equals("FOUND"))throw new IllegalArgumentException("Enter a LOST ID and a FOUND ID in the correct fields.");if(Boolean.parseBoolean(l[4])||Boolean.parseBoolean(fnd[4]))throw new IllegalArgumentException("One or both reports are already resolved.");if(!l[1].equalsIgnoreCase(email))throw new IllegalArgumentException("Email does not match the LOST report owner.");if(!norm(l[2]).equals(norm(fnd[2]))||!norm(l[3]).equals(norm(fnd[3])))throw new IllegalArgumentException("These reports do not match by item name and category.");try(PreparedStatement p=c.prepareStatement("UPDATE reports SET resolved=TRUE WHERE id IN (?,?)")){p.setString(1,lost);p.setString(2,found);p.executeUpdate();}c.commit();send(ex,200,"application/json","{\"message\":\"Return confirmed. Both reports are now resolved.\"}");}return; }
      send(ex,404,"application/json","{\"error\":\"Not found\"}");
    } catch(IllegalArgumentException e){send(ex,400,"application/json","{\"error\":"+j(e.getMessage())+"}");}catch(Exception e){e.printStackTrace();send(ex,500,"application/json","{\"error\":\"Server error. Please try again later.\"}");}
  }
  static String matches(Connection c,String type,String item,String cat)throws SQLException{String other=type.equals("LOST")?"FOUND":"LOST";try(PreparedStatement p=c.prepareStatement("SELECT id,type,student_name,item_name,category,description,location FROM reports WHERE resolved=FALSE AND type=? AND LOWER(TRIM(item_name))=LOWER(TRIM(?)) AND LOWER(TRIM(category))=LOWER(TRIM(?)) ORDER BY created_at DESC")){p.setString(1,other);p.setString(2,item);p.setString(3,cat);try(ResultSet r=p.executeQuery()){return rows(r);}}}
  static String rows(ResultSet r)throws SQLException{StringBuilder b=new StringBuilder("[");boolean first=true;while(r.next()){if(!first)b.append(',');first=false;b.append("{\"id\":").append(j(r.getString(1))).append(",\"type\":").append(j(r.getString(2))).append(",\"studentName\":").append(j(r.getString(3))).append(",\"itemName\":").append(j(r.getString(4))).append(",\"category\":").append(j(r.getString(5))).append(",\"description\":").append(j(r.getString(6))).append(",\"location\":").append(j(r.getString(7))).append('}');}return b.append(']').toString();}
  static Map<String,String> params(String raw)throws IOException{Map<String,String> m=new HashMap<>();if(raw==null)return m;for(String x:raw.split("&")){String[] a=x.split("=",2);m.put(URLDecoder.decode(a[0],"UTF-8"),a.length>1?URLDecoder.decode(a[1],"UTF-8"):"");}return m;}
  static String req(Map<String,String> m,String k){String v=m.getOrDefault(k,"").trim();if(v.isEmpty())throw new IllegalArgumentException("Please fill in all required fields.");return v;}
  static String norm(String x){return x.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");}
  static String j(String x){if(x==null)return "null";return "\""+x.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";}
  static void send(HttpExchange e,int code,String type,String body)throws IOException{byte[] b=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type",type+ (type.startsWith("text/")?"; charset=utf-8":"; charset=utf-8"));e.getResponseHeaders().set("Cache-Control","no-store");e.sendResponseHeaders(code,b.length);try(OutputStream o=e.getResponseBody()){o.write(b);}}
}
