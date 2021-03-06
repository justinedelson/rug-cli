package com.atomist.rug.cli.command.list;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.InvalidVersionSpecificationException;
import org.eclipse.aether.version.VersionConstraint;
import org.eclipse.aether.version.VersionScheme;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.util.StringUtils;

import com.atomist.rug.cli.Constants;
import com.atomist.rug.cli.command.AbstractAnnotationBasedCommand;
import com.atomist.rug.cli.command.annotation.Command;
import com.atomist.rug.cli.command.annotation.Option;
import com.atomist.rug.cli.output.ProgressReportingOperationRunner;
import com.atomist.rug.cli.output.Style;
import com.atomist.rug.cli.settings.Settings;
import com.atomist.rug.resolver.ArtifactDescriptor;
import com.atomist.rug.resolver.ArtifactDescriptor.Extension;
import com.atomist.rug.resolver.ArtifactDescriptor.Scope;
import com.atomist.rug.resolver.DefaultArtifactDescriptor;

public class ListCommand extends AbstractAnnotationBasedCommand {

    @Command
    public void run(@Option("filter") Properties filter, Settings settings) {

        Map<String, List<ArtifactDescriptor>> archives = new ProgressReportingOperationRunner<Map<String, List<ArtifactDescriptor>>>(
                "Listing local archives")
                        .run(indicator -> collectArchives(filter, settings).stream().collect(
                                Collectors.groupingBy(a -> a.group() + ":" + a.artifact())));

        log.newline();
        log.info(
                Style.cyan(Constants.DIVIDER) + " " + Style.bold("Local Archives")
                        + (archives.keySet().size() > 0
                                ? " (" + archives.keySet().size()
                                        + " " + com.atomist.rug.cli.utils.StringUtils
                                                .puralize("archive", archives.keySet())
                                        + " found)"
                                : ""));

        if (archives.isEmpty()) {
            log.info(Style.yellow("  No matching archives found"));
            log.newline();
        }
        else {
            archives.entrySet().stream().sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(a -> printArchive(a.getKey(), a.getValue()));
            if (Constants.isShell()) {
                log.info("\nFor more information on specific archive version, run:\n"
                        + "  shell ARCHIVE -a VERSION\nfollowed by:\n  describe archive");
            }
            else {
                log.info("\nFor more information on specific archive version, run:\n"
                        + "  %sdescribe archive ARCHIVE -a VERSION", Constants.command());
            }
        }
    }

    private void printArchive(String groupArtifact, List<ArtifactDescriptor> versions) {
        List<String> versionStrings = versions.stream()
                .sorted((v1, v2) -> v2.version().compareTo(v1.version()))
                .map(ArtifactDescriptor::version).collect(Collectors.toList());
        // underline the highest version
        versionStrings.set(0, Style.underline(versionStrings.get(0)));
        log.info(Style.yellow("  %s", groupArtifact) + " (%s)",
                StringUtils.collectionToDelimitedString(versionStrings, ", "));
    }

    private List<ArtifactDescriptor> collectArchives(Properties filter, Settings settings) {
        File repo = new File(settings.getLocalRepository().path());
        URI repoHome = repo.toURI();
        if (repo.exists()) {
            Collection<File> archives = FileUtils.listFiles(repo, new String[] { "zip" }, true)
                    .stream().sorted(Comparator.comparing(File::getAbsolutePath))
                    .collect(Collectors.toList());

            String group = filter.getProperty("group");
            if (group != null) {
                String pattern = "/**/" + group.replace(".", File.separator) + "/*/*/*.zip";
                PathMatcher matcher = new AntPathMatcher();
                archives = archives.stream()
                        .filter(a -> matcher.match(pattern, a.getAbsolutePath()))
                        .collect(Collectors.toList());
            }
            String artifact = filter.getProperty("artifact");
            if (artifact != null) {
                String pattern = "/**/" + artifact + "/*/*.zip";
                PathMatcher matcher = new AntPathMatcher();
                archives = archives.stream()
                        .filter(a -> matcher.match(pattern, a.getAbsolutePath()))
                        .collect(Collectors.toList());
            }
            String version = filter.getProperty("version");
            if (version != null) {
                VersionScheme scheme = new GenericVersionScheme();
                try {
                    VersionConstraint constraint = scheme.parseVersionConstraint(version);
                    archives = archives.stream().filter(a -> {
                        String v = a.getParentFile().getName();
                        try {
                            return constraint.containsVersion(scheme.parseVersion(v));
                        }
                        catch (InvalidVersionSpecificationException e) {
                            return false;
                        }
                    }).collect(Collectors.toList());
                }
                catch (InvalidVersionSpecificationException e) {
                    log.info("Invalid version constraint %s specified in filter", version);
                }
            }
            return archives.stream().filter(f -> {
                // filter out all non-Rug archives
                File metadata = new File(f.getParentFile(),
                        f.getName().replaceAll(".zip$", "-metadata.json"));
                return metadata.exists();
            }).map(f -> {
                URI relativeUri = repoHome.relativize(f.toURI());
                List<String> segments = new ArrayList<>(
                        Arrays.asList(relativeUri.toString().split("/")));
                // last segment is the actual file name
                segments.remove(segments.size() - 1);
                // last segments is version
                String v = segments.remove(segments.size() - 1);
                // second to last is artifact
                String a = segments.remove(segments.size() - 1);
                // remaining segments are group
                String g = StringUtils.collectionToDelimitedString(segments, ".");
                return new DefaultArtifactDescriptor(g, a, v, Extension.ZIP, Scope.COMPILE,
                        f.getAbsolutePath());
            }).collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}
