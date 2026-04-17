# --- build stage ---
FROM node:20-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Build browser bundle only (no SSR) — outputs to dist/promptle-app/browser
RUN npx ng build --configuration production

# --- runtime stage ---
FROM nginx:1.27-alpine
COPY --from=build /src/dist/promptle-app/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
