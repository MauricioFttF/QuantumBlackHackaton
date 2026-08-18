# QuantumBlackHackaton

## Banco de dados vetorial

Usamos PostgreSQL com a extensão [pgvector](https://github.com/pgvector/pgvector) para armazenar embeddings e realizar busca por similaridade semântica (RAG). A extensão é habilitada automaticamente via `CREATE EXTENSION IF NOT EXISTS vector;` no banco `hackathondb`.

Imagem Docker utilizada: `pgvector/pgvector:pg16`.