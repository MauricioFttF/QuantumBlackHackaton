package com.seuprojeto.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class EventDataDTO {

    private Evento evento;
    private List<AgendaItem> agenda;
    private List<Palestrante> palestrantes;
    private List<Artigo> artigos;
    private List<MateriaImprensa> materias_imprensa;
    private List<FaqItem> faq_sugerido;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Evento {
        private String nome;
        private String data_extenso;
        private String horario_inicio;
        private String horario_fim;
        private Local local;
        private String tema_geral;
        private String descricao_longa;

        public String getNome() { return nome; }
        public void setNome(String nome) { this.nome = nome; }
        public String getData_extenso() { return data_extenso; }
        public void setData_extenso(String data_extenso) { this.data_extenso = data_extenso; }
        public String getHorario_inicio() { return horario_inicio; }
        public void setHorario_inicio(String horario_inicio) { this.horario_inicio = horario_inicio; }
        public String getHorario_fim() { return horario_fim; }
        public void setHorario_fim(String horario_fim) { this.horario_fim = horario_fim; }
        public Local getLocal() { return local; }
        public void setLocal(Local local) { this.local = local; }
        public String getTema_geral() { return tema_geral; }
        public void setTema_geral(String tema_geral) { this.tema_geral = tema_geral; }
        public String getDescricao_longa() { return descricao_longa; }
        public void setDescricao_longa(String descricao_longa) { this.descricao_longa = descricao_longa; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Local {
        private String nome;
        private String endereco;
        private String cidade;
        private String estado;

        public String getNome() { return nome; }
        public void setNome(String nome) { this.nome = nome; }
        public String getEndereco() { return endereco; }
        public void setEndereco(String endereco) { this.endereco = endereco; }
        public String getCidade() { return cidade; }
        public void setCidade(String cidade) { this.cidade = cidade; }
        public String getEstado() { return estado; }
        public void setEstado(String estado) { this.estado = estado; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgendaItem {
        private String horario_inicio;
        private String horario_fim;
        private String titulo;
        private String descricao;
        private List<Subsessao> subsessoes;

        public String getHorario_inicio() { return horario_inicio; }
        public void setHorario_inicio(String horario_inicio) { this.horario_inicio = horario_inicio; }
        public String getHorario_fim() { return horario_fim; }
        public void setHorario_fim(String horario_fim) { this.horario_fim = horario_fim; }
        public String getTitulo() { return titulo; }
        public void setTitulo(String titulo) { this.titulo = titulo; }
        public String getDescricao() { return descricao; }
        public void setDescricao(String descricao) { this.descricao = descricao; }
        public List<Subsessao> getSubsessoes() { return subsessoes; }
        public void setSubsessoes(List<Subsessao> subsessoes) { this.subsessoes = subsessoes; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Subsessao {
        private String titulo;
        private String descricao;

        public String getTitulo() { return titulo; }
        public void setTitulo(String titulo) { this.titulo = titulo; }
        public String getDescricao() { return descricao; }
        public void setDescricao(String descricao) { this.descricao = descricao; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Palestrante {
        private String nome;
        private String cargo;
        private String empresa;
        private String localizacao;
        private String biografia;

        public String getNome() { return nome; }
        public void setNome(String nome) { this.nome = nome; }
        public String getCargo() { return cargo; }
        public void setCargo(String cargo) { this.cargo = cargo; }
        public String getEmpresa() { return empresa; }
        public void setEmpresa(String empresa) { this.empresa = empresa; }
        public String getLocalizacao() { return localizacao; }
        public void setLocalizacao(String localizacao) { this.localizacao = localizacao; }
        public String getBiografia() { return biografia; }
        public void setBiografia(String biografia) { this.biografia = biografia; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Artigo {
        private String titulo_original;
        private String titulo_traduzido;
        private String introducao;
        private List<PontoChave> pontos_chave;
        private List<PilarEstrategico> pilares_estrategicos;
        private List<EstatisticaChave> estatisticas_chave;
        private List<Acelerador> aceleradores_de_crescimento;

        public String getTitulo_original() { return titulo_original; }
        public void setTitulo_original(String titulo_original) { this.titulo_original = titulo_original; }
        public String getTitulo_traduzido() { return titulo_traduzido; }
        public void setTitulo_traduzido(String titulo_traduzido) { this.titulo_traduzido = titulo_traduzido; }
        public String getIntroducao() { return introducao; }
        public void setIntroducao(String introducao) { this.introducao = introducao; }
        public List<PontoChave> getPontos_chave() { return pontos_chave; }
        public void setPontos_chave(List<PontoChave> pontos_chave) { this.pontos_chave = pontos_chave; }
        public List<PilarEstrategico> getPilares_estrategicos() { return pilares_estrategicos; }
        public void setPilares_estrategicos(List<PilarEstrategico> pilares_estrategicos) { this.pilares_estrategicos = pilares_estrategicos; }
        public List<EstatisticaChave> getEstatisticas_chave() { return estatisticas_chave; }
        public void setEstatisticas_chave(List<EstatisticaChave> estatisticas_chave) { this.estatisticas_chave = estatisticas_chave; }
        public List<Acelerador> getAceleradores_de_crescimento() { return aceleradores_de_crescimento; }
        public void setAceleradores_de_crescimento(List<Acelerador> aceleradores_de_crescimento) { this.aceleradores_de_crescimento = aceleradores_de_crescimento; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PontoChave {
        private Integer numero;
        private String titulo;
        private String conteudo;

        public Integer getNumero() { return numero; }
        public void setNumero(Integer numero) { this.numero = numero; }
        public String getTitulo() { return titulo; }
        public void setTitulo(String titulo) { this.titulo = titulo; }
        public String getConteudo() { return conteudo; }
        public void setConteudo(String conteudo) { this.conteudo = conteudo; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PilarEstrategico {
        private Integer numero;
        private String titulo;
        private String descricao;
        private List<Setor> setores;

        public Integer getNumero() { return numero; }
        public void setNumero(Integer numero) { this.numero = numero; }
        public String getTitulo() { return titulo; }
        public void setTitulo(String titulo) { this.titulo = titulo; }
        public String getDescricao() { return descricao; }
        public void setDescricao(String descricao) { this.descricao = descricao; }
        public List<Setor> getSetores() { return setores; }
        public void setSetores(List<Setor> setores) { this.setores = setores; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Setor {
        private String nome;
        private String descricao;

        public String getNome() { return nome; }
        public void setNome(String nome) { this.nome = nome; }
        public String getDescricao() { return descricao; }
        public void setDescricao(String descricao) { this.descricao = descricao; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EstatisticaChave {
        private String titulo;
        private String dado;
        private String fonte_citada;

        public String getTitulo() { return titulo; }
        public void setTitulo(String titulo) { this.titulo = titulo; }
        public String getDado() { return dado; }
        public void setDado(String dado) { this.dado = dado; }
        public String getFonte_citada() { return fonte_citada; }
        public void setFonte_citada(String fonte_citada) { this.fonte_citada = fonte_citada; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Acelerador {
        private String titulo;
        private String descricao;

        public String getTitulo() { return titulo; }
        public void setTitulo(String titulo) { this.titulo = titulo; }
        public String getDescricao() { return descricao; }
        public void setDescricao(String descricao) { this.descricao = descricao; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MateriaImprensa {
        private String titulo;
        private String data;
        private List<Participante> participantes;
        private String resumo;

        public String getTitulo() { return titulo; }
        public void setTitulo(String titulo) { this.titulo = titulo; }
        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
        public List<Participante> getParticipantes() { return participantes; }
        public void setParticipantes(List<Participante> participantes) { this.participantes = participantes; }
        public String getResumo() { return resumo; }
        public void setResumo(String resumo) { this.resumo = resumo; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Participante {
        private String nome;
        private String cargo;

        public String getNome() { return nome; }
        public void setNome(String nome) { this.nome = nome; }
        public String getCargo() { return cargo; }
        public void setCargo(String cargo) { this.cargo = cargo; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FaqItem {
        private String pergunta;
        private String resposta;

        public String getPergunta() { return pergunta; }
        public void setPergunta(String pergunta) { this.pergunta = pergunta; }
        public String getResposta() { return resposta; }
        public void setResposta(String resposta) { this.resposta = resposta; }
    }

    public Evento getEvento() { return evento; }
    public void setEvento(Evento evento) { this.evento = evento; }
    public List<AgendaItem> getAgenda() { return agenda; }
    public void setAgenda(List<AgendaItem> agenda) { this.agenda = agenda; }
    public List<Palestrante> getPalestrantes() { return palestrantes; }
    public void setPalestrantes(List<Palestrante> palestrantes) { this.palestrantes = palestrantes; }
    public List<Artigo> getArtigos() { return artigos; }
    public void setArtigos(List<Artigo> artigos) { this.artigos = artigos; }
    public List<MateriaImprensa> getMaterias_imprensa() { return materias_imprensa; }
    public void setMaterias_imprensa(List<MateriaImprensa> materias_imprensa) { this.materias_imprensa = materias_imprensa; }
    public List<FaqItem> getFaq_sugerido() { return faq_sugerido; }
    public void setFaq_sugerido(List<FaqItem> faq_sugerido) { this.faq_sugerido = faq_sugerido; }
}