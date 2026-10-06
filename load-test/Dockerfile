FROM node:24-bookworm-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6
WORKDIR /app
COPY load-test/ ./load-test/
USER node
ENV NODE_OPTIONS="--max-old-space-size=128"
ENTRYPOINT ["node"]
