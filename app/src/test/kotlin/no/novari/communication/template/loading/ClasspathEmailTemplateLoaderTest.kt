package no.novari.communication.template.loading

import no.novari.communication.template.definition.TemplateDefinitionException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ClasspathEmailTemplateLoaderTest {
    @Test
    fun `the template id is derived from the team and template directories`() {
        val catalog = ClasspathEmailTemplateLoader(root = "loader-test/valid").load()

        assertThat(catalog.ids).containsExactly("team/eksempel")
        assertThat(catalog.find("team/eksempel")?.variables).containsOnlyKeys("navn")
    }

    @Test
    fun `a template without body html is rejected`() {
        assertThatThrownBy { ClasspathEmailTemplateLoader(root = "loader-test/missing-body").load() }
            .isInstanceOf(TemplateDefinitionException::class.java)
            .hasMessage("Ugyldig mal 'team/uten-body': body.html mangler")
    }

    @Test
    fun `all production templates are valid`() {
        assertThatCode { ClasspathEmailTemplateLoader().load() }.doesNotThrowAnyException()
    }
}
