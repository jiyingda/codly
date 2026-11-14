package com.jiyingda.codly.skill;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class SkillLoaderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void loadsAdditionalDirectoriesAndKeepsFirstDefinitionForDuplicateNames() throws Exception {
        Path first = temporary.newFolder("first").toPath();
        Path second = temporary.newFolder("second").toPath();
        skill(first, "alpha", "first definition");
        skill(second, "alpha", "second definition");
        skill(second, "beta", "additional skill");
        Path invalid = Files.createDirectory(second.resolve("invalid"));
        Files.writeString(invalid.resolve("SKILL.md"), "missing frontmatter");
        List<Skill> skills = SkillLoader.loadAll(List.of(first, second, second.resolve("missing")));
        assertEquals(List.of("alpha", "beta"), skills.stream().map(Skill::getName).toList());
        assertEquals("first definition", skills.get(0).getDescription());
        assertEquals(first.resolve("alpha"), skills.get(0).getDir());
    }

    private void skill(Path root, String name, String description) throws Exception {
        Path directory = Files.createDirectory(root.resolve(name));
        Files.writeString(directory.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\nFollow the configured skill.\n");
    }
}
