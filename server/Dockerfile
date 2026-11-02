FROM node:22-slim
WORKDIR /srv/vibe
COPY package*.json ./
RUN npm ci --omit=dev
COPY src ./src
ENV NODE_ENV=production PORT=8080
USER node
EXPOSE 8080
CMD ["node", "src/index.js"]
