# TeamSheet

TeamSheet generates football rosters using a random shuffle, skill balancing, or skill and position balancing.

## Run locally

Install Java 21 and Node.js, then start the API from the repository root.

```sh
./gradlew bootRun
```

In another terminal, start the React app.

```sh
cd frontend
npm ci
npm run dev
```

Open the local URL printed by Vite. Vite proxies API requests to Spring Boot on port 8080.

Run the backend tests with `./gradlew test`. Run `npm run build` from `frontend` to type-check and build the frontend.

## Enable email sign-in with Resend

Email sign-in is available with the `roster` Spring profile. Set these backend
environment variables locally or on Render:

| Variable | Value |
| --- | --- |
| `RESEND_API_KEY` | Resend API key for transactional email |
| `RESEND_SENDER_EMAIL` | Sender email verified in Resend |
| `EMAIL_CODE_SECRET` | A separate secret for signing stored codes. Generate it with `openssl rand -hex 32`. |

Keep both secrets in the backend environment. Flyway creates the email identity
and code tables on startup. Email sign-in appears when the three settings are
present. Google sign-in continues to use the existing Google credentials.

For initial testing, set `RESEND_SENDER_EMAIL=onboarding@resend.dev` and request
a code using the email address associated with your Resend account. To send to
other users, verify your own domain in Resend and use a sender on that domain.
Resend setup is documented at https://resend.com/docs/knowledge-base/403-error-resend-dev-domain.

To use email for an existing Google roster, sign in with Google and select
**Link email sign-in**. Enter the email address and verify the code. Either
method can then open the same roster. Starting with email sign-in creates a
separate roster if the email has never been linked.

To run the real PostgreSQL email sign-in tests, set `TEAMSHEET_AUTH_TEST_JDBC`
to a disposable PostgreSQL database URL. Optionally set
`TEAMSHEET_AUTH_TEST_USER` and `TEAMSHEET_AUTH_TEST_PASSWORD`. The tests replace
Resend with a stub and send no email.
