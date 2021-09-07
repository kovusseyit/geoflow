package html

import io.ktor.html.*
import kotlinx.html.*

open class BasePage: Template<HTML> {

    val content = Placeholder<FlowContent>()
    val script = Placeholder<FlowContent>()

    fun setContent(addContent: FlowContent.() -> Unit) {
        content {
            this.addContent()
        }
    }
    fun setScript(addScript: FlowContent.() -> Unit) {
        script {
            this.addScript()
        }
    }

    override fun HTML.apply() {
        lang = "en-US"
        head {
            title("GeoFlow")
            meta {
                charset = "utf-8"
            }
            meta {
                name = "author"
                content = "ClasicRando"
            }
            link {
                rel = "stylesheet"
                href = "https://unpkg.com/bootstrap-table@1.18.3/dist/bootstrap-table.min.css"
            }
            link {
                rel = "stylesheet"
                href = "https://stackpath.bootstrapcdn.com/bootstrap/4.3.1/css/bootstrap.min.css"
                integrity = "sha384-ggOyR0iXCbMQv3Xipma34MD+dH/1fQ784/j6cY/iJTQUOhcWr7x9JvoRxT2MZw1T"
                attributes["crossorigin"] = "anonymous"
            }
            link {
                rel = "stylesheet"
                href = "https://use.fontawesome.com/releases/v5.6.3/css/all.css"
                integrity = "sha384-UHRtZLI+pbxtHCWp1t77Bi1L4ZtiqrqD80Kn4Z8NTSRyMA2Fd33n5dQ8lWUE00s/"
                attributes["crossorigin"] = "anonymous"
            }
        }
        body {
            div("container-fluid") {
                nav(classes = "navbar navbar-expand-lg navbar-dark bg-dark") {
                    h3(classes = "navbar-text mx-2") {
                        +"GeoFlow"
                    }
                    ul(classes = "navbar-nav") {
                        li(classes = "nav-item") {
                            a(classes = "nav-link") {
                                href = "/index"
                                +"Home"
                            }
                        }
                        li(classes = "nav-item") {
                            a(classes = "nav-link") {
                                href = "/logout"
                                +"Logout"
                            }
                        }
                    }
                }
                insert(content)
                insert(script)
                script {
                    src = "https://unpkg.com/bootstrap-table@1.18.3/dist/bootstrap-table.min.js"
                }
                script {
                    src = "https://cdn.jsdelivr.net/npm/jquery/dist/jquery.min.js"
                }
                script {
                    src = "https://cdn.jsdelivr.net/npm/popper.js@1.16.0/dist/umd/popper.min.js"
                }
                script {
                    src = "https://stackpath.bootstrapcdn.com/bootstrap/4.3.1/js/bootstrap.min.js"
                    integrity = "sha384-JjSmVgyd0p3pXB1rRibZUAYoIIy6OrQ6VrjIEaFf/nJGzIxFDsf4x0xIM+B07jRM"
                    attributes["crossorigin"] = "anonymous"
                }
            }
        }
    }
}