package io.zer0.muse.data.prompttemplate

import android.content.Context
import io.zer0.muse.R
import kotlinx.serialization.Serializable

/**
 * v1.58: Prompt Plantilla — Palabras de indicación de escena preestablecidas(escribiendo/Traducción/Resumen/código/aprender/creatividad).
 *
 * Los usuarios pueden ingresar a la biblioteca de plantillas desde el campo de entrada más el menú,Seleccione una plantilla e inserte el cuadro de entrada con un clic.
 * con QuickMessage(frases abreviadas chips)distinguir:La biblioteca de plantillas está completa con indicaciones largas.
 *
 * Descripción de la sintaxis del marcador de posición(L-PT1):
 * - PromptTemplate Usar marcador de posición de corchete único(Como por ejemplo {Tema}),Debe ser reemplazado manualmente por el usuario o por la persona que llama.;
 *   El contenido de la plantilla se inserta en el cuadro de entrada solo como texto inicial.,Sin renderizado variable automático.
 * - QuickMessage Utilice marcadores de posición de doble corchete(Como por ejemplo {{input}}),por QuickMessageRepository.renderTemplate
 *   renderizado automático(input/clipboard/date). Los dos tienen semántica diferente.,No mezclar.
 *
 * @param category Clasificación:escribiendo/Traducción/Resumen/código/aprender/creatividad(ver [CATEGORIES] constante,Free String no realiza una verificación de enumeración sólida)
 * @param builtIn Si tener plantillas integradas(El integrado no se puede eliminar)
 */
@Serializable
data class PromptTemplate(
    val id: String,
    val name: String,
    val category: String,
    val content: String,
    val builtIn: Boolean = false,
) {
    companion object {
        val CATEGORIES = listOf("Writing", "Translation", "Summary", "Code", "Learning", "Creative")

        fun getBuiltInPromptTemplates(context: Context): List<PromptTemplate> = listOf(
            PromptTemplate(
                "bi_write_email",
                context.getString(R.string.prompt_template_name_write_email),
                context.getString(R.string.prompt_template_category_writing),
                context.getString(R.string.prompt_template_content_write_email),
            ),
            PromptTemplate(
                "bi_write_article",
                context.getString(R.string.prompt_template_name_write_article),
                context.getString(R.string.prompt_template_category_writing),
                context.getString(R.string.prompt_template_content_write_article),
            ),
            PromptTemplate(
                "bi_translate_zh_en",
                context.getString(R.string.prompt_template_name_translate_zh_en),
                context.getString(R.string.prompt_template_category_translation),
                context.getString(R.string.prompt_template_content_translate_zh_en),
            ),
            PromptTemplate(
                "bi_translate_en_zh",
                context.getString(R.string.prompt_template_name_translate_en_zh),
                context.getString(R.string.prompt_template_category_translation),
                context.getString(R.string.prompt_template_content_translate_en_zh),
            ),
            PromptTemplate(
                "bi_summarize",
                context.getString(R.string.prompt_template_name_summarize),
                context.getString(R.string.prompt_template_category_summary),
                context.getString(R.string.prompt_template_content_summarize),
            ),
            PromptTemplate(
                "bi_summarize_meeting",
                context.getString(R.string.prompt_template_name_summarize_meeting),
                context.getString(R.string.prompt_template_category_summary),
                context.getString(R.string.prompt_template_content_summarize_meeting),
            ),
            PromptTemplate(
                "bi_code_review",
                context.getString(R.string.prompt_template_name_code_review),
                context.getString(R.string.prompt_template_category_code),
                context.getString(R.string.prompt_template_content_code_review),
            ),
            PromptTemplate(
                "bi_code_explain",
                context.getString(R.string.prompt_template_name_code_explain),
                context.getString(R.string.prompt_template_category_code),
                context.getString(R.string.prompt_template_content_code_explain),
            ),
            PromptTemplate(
                "bi_code_refactor",
                context.getString(R.string.prompt_template_name_code_refactor),
                context.getString(R.string.prompt_template_category_code),
                context.getString(R.string.prompt_template_content_code_refactor),
            ),
            PromptTemplate(
                "bi_learn_concept",
                context.getString(R.string.prompt_template_name_learn_concept),
                context.getString(R.string.prompt_template_category_learning),
                context.getString(R.string.prompt_template_content_learn_concept),
            ),
            PromptTemplate(
                "bi_learn_quiz",
                context.getString(R.string.prompt_template_name_learn_quiz),
                context.getString(R.string.prompt_template_category_learning),
                context.getString(R.string.prompt_template_content_learn_quiz),
            ),
            PromptTemplate(
                "bi_creative_story",
                context.getString(R.string.prompt_template_name_creative_story),
                context.getString(R.string.prompt_template_category_creative),
                context.getString(R.string.prompt_template_content_creative_story),
            ),
            PromptTemplate(
                "bi_creative_brainstorm",
                context.getString(R.string.prompt_template_name_creative_brainstorm),
                context.getString(R.string.prompt_template_category_creative),
                context.getString(R.string.prompt_template_content_creative_brainstorm),
            ),
        )
    }
}
