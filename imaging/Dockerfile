# The photo processor: Node + sharp, no storage. Deployed as its own Fly app with no public ports; the backend
# reaches it on Fly's private network (docs/DEPLOY.md).
FROM node:22-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY src ./src
USER node
EXPOSE 8081
CMD ["node", "src/server.js"]
