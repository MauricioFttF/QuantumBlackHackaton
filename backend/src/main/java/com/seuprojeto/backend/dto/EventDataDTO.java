package com.seuprojeto.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class EventDataDTO {

    private Evento evento;
    private List<AgendaItem> agenda;
    private List<Palestrante> palestrantes;
    private List<Artigo> artigos;
    private List<Materia> materias;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Evento {
        private String data;
        private String local;
        private String tema_geral;

        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
        public String getLocal() { return local; }
        public void setLocal(String local) { this.local = local; }
        public String getTema_geral() { return tema_geral; }
        public void setTema_geral(String tema_geral) { this.tema_geral = tema_geral; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgendaItem {
        private String horario;
        private String tema_da_palestra;
        private String palestrante_relacionado;

        public String getHorario() { return horario; }
        public void setHorario(String horario) { this.horario = horario; }
        public String getTema_da_palestra() { return tema_da_palestra; }
        public void setTema_da_palestra(String tema_da_palestra) { this.tema_da_palestra = tema_da_palestra; }
        public String getPalestrante_relacionado() { return palestrante_relacionado; }
        public void setPalestrante_relacionado(String palestrante_relacionado) { this.palestrante_relacionado = palestrante_relacionado; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Palestrante {
        private String nome;
        private String cargo;
        private String biografia;

        public String getNome() { return nome; }
        public void setNome(String nome) { this.nome = nome; }
        public String getCargo() { return cargo; }
        public void setCargo(String cargo) { this.cargo = cargo; }
        public String getBiografia() { return biografia; }
        public void setBiografia(String biografia) { this.biografia = biografia; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Artigo {
        private String titulo_original;
        private String titulo_traduzido;
        private String resumo;

        public String getTitulo_original() { return titulo_original; }
        public void setTitulo_original(String titulo_original) { this.titulo_original = titulo_original; }
        public String getTitulo_traduzido() { return titulo_traduzido; }
        public void setTitulo_traduzido(String titulo_traduzido) { this.titulo_traduzido = titulo_traduzido; }
        public String getResumo() { return resumo; }
        public void setResumo(String resumo) { this.resumo = resumo; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Materia {
        private String titulo;
        private String data;
        private String participantes_mencionados;
        private String resumo;

        public String getTitulo() { return titulo; }
        public void setTitulo(String titulo) { this.titulo = titulo; }
        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
        public String getParticipantes_mencionados() { return participantes_mencionados; }
        public void setParticipantes_mencionados(String participantes_mencionados) { this.participantes_mencionados = participantes_mencionados; }
        public String getResumo() { return resumo; }
        public void setResumo(String resumo) { this.resumo = resumo; }
    }

    public Evento getEvento() { return evento; }
    public void setEvento(Evento evento) { this.evento = evento; }
    public List<AgendaItem> getAgenda() { return agenda; }
    public void setAgenda(List<AgendaItem> agenda) { this.agenda = agenda; }
    public List<Palestrante> getPalestrantes() { return palestrantes; }
    public void setPalestrantes(List<Palestrante> palestrantes) { this.palestrantes = palestrantes; }
    public List<Artigo> getArtigos() { return artigos; }
    public void setArtigos(List<Artigo> artigos) { this.artigos = artigos; }
    public List<Materia> getMaterias() { return materias; }
    public void setMaterias(List<Materia> materias) { this.materias = materias; }
}