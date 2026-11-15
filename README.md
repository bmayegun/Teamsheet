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
