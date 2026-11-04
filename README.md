# SRM Find — cloud deployment MVP

A lightweight Java 17 web application. The Java server serves the existing responsive website and exposes its API. PostgreSQL stores shared reports permanently; no local file storage is used in cloud deployment.

## Deploy on Render
1. Push the complete contents of this folder to the `main` branch of your GitHub repository.
2. Create a PostgreSQL database (Neon or Render PostgreSQL). Keep its credentials private.
3. In Render, create a Web Service from the GitHub repository and select Docker.
4. Add environment variables: `DATABASE_URL`, `DB_USER`, `DB_PASSWORD`. `PORT` is provided by Render.
5. Deploy. Health check path: `/health`.

`DATABASE_URL` may be a JDBC URL (`jdbc:postgresql://host:5432/db?sslmode=require`) or a PostgreSQL URL (`postgresql://host:5432/db?sslmode=require`). For the latter, set `DB_USER` and `DB_PASSWORD` separately.

## Local development
Requires JDK 17+ and Maven 3.9+, plus PostgreSQL. Set `DATABASE_URL`, `DB_USER`, `DB_PASSWORD`, then run `mvn package` and `java -jar target/srm-find-1.0.0.jar`.

## Important
This is a functional technical MVP, not yet approved for unrestricted campus-wide use. Email is only format-validated, not verified. Before inviting students, implement SRM email OTP/SSO, owner authentication/authorization for claims, rate limiting, abuse reporting, moderation, privacy/retention rules, and security review. A possible match is not proof of ownership. Do not commit database credentials.
