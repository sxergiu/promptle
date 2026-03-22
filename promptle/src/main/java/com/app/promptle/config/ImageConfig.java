package com.app.promptle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "image")
public class ImageConfig {

    private Generation generation = new Generation();
    private Storage storage = new Storage();

    public Generation getGeneration() { return generation; }
    public void setGeneration(Generation generation) { this.generation = generation; }

    public Storage getStorage() { return storage; }
    public void setStorage(Storage storage) { this.storage = storage; }

    public static class Generation {
        private String provider;
        private Comfyui comfyui = new Comfyui();

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public Comfyui getComfyui() { return comfyui; }
        public void setComfyui(Comfyui comfyui) { this.comfyui = comfyui; }

        public static class Comfyui {
            private String url;

            public String getUrl() { return url; }
            public void setUrl(String url) { this.url = url; }
        }
    }

    public static class Storage {
        private String provider;
        private Local local = new Local();

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public Local getLocal() { return local; }
        public void setLocal(Local local) { this.local = local; }

        public static class Local {
            private String path;

            public String getPath() { return path; }
            public void setPath(String path) { this.path = path; }
        }
    }
}
