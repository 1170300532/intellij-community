// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.jarRepository.services.artifactory;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.intellij.jarRepository.RemoteRepositoryDescription;
import com.intellij.jarRepository.RepositoryArtifactDescription;
import com.intellij.jarRepository.services.MavenRepositoryService;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.ObjectUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @author Gregory.Shrago
 */
public class ArtifactoryRepositoryService extends MavenRepositoryService {
  @NotNull
  @Override
  public String getDisplayName() {
    return "Artifactory";
  }

  @NotNull
  @Override
  public List<RemoteRepositoryDescription> getRepositories(@NotNull String url) throws IOException {
    try {
      final Gson gson = new Gson();
      final InputStreamReader stream =
        new InputStreamReader(new Endpoint.Repositories(url).getRepositoryDetailsListJson(null).getInputStream(), StandardCharsets.UTF_8);
      final ArtifactoryModel.RepositoryType[] repos = gson.fromJson(stream, ArtifactoryModel.RepositoryType[].class);
      final List<RemoteRepositoryDescription> result = new ArrayList<>(repos.length);
      for (ArtifactoryModel.RepositoryType repo : repos) {
        result.add(convert(repo));
      }
      return result;
    }
    catch (JsonSyntaxException e) {
      return Collections.emptyList();
    }
    catch (Exception e) {
      throw new IOException(e);
    }
  }

  private static RemoteRepositoryDescription convert(ArtifactoryModel.RepositoryType repo) {
    return new RemoteRepositoryDescription(repo.key, ObjectUtils.notNull(repo.description, repo.key), repo.url);
  }

  @NotNull
  @Override
  public List<RepositoryArtifactDescription> findArtifacts(@NotNull String url, @NotNull RepositoryArtifactDescription template) throws IOException {
    try {
      final String packaging = StringUtil.notNullize(template.getPackaging());
      final ArrayList<RepositoryArtifactDescription> artifacts = new ArrayList<>();
      final Gson gson = new Gson();
      final String className = template.getClassNames();
      if (className == null || className.length() == 0) {
        final String name = StringUtil.join(Arrays.asList(template.getGroupId(), template.getArtifactId(), template.getVersion()), ":");
        final InputStream stream = new Endpoint.Search.Artifact(url).getArtifactSearchResultJson(name, null).getInputStream();

        final ArtifactoryModel.GavcResults results = stream == null? null : gson.fromJson(new InputStreamReader(stream,
                                                                                                                StandardCharsets.UTF_8), ArtifactoryModel.GavcResults.class);
        if (results != null && results.results != null) {
          for (ArtifactoryModel.GavcResult result : results.results) {
            if (!result.uri.endsWith(packaging)) continue;
            artifacts.add(convertArtifactInfo(result.uri, url, null));
          }
        }
      }
      else {
        // IDEA-58225
        final String searchString = className.endsWith("*") || className.endsWith("?") ? className : className + ".class";
        final InputStream stream = new Endpoint.Search.Archive(url).getArchiveSearchResultJson(searchString, null).getInputStream();

        final ArtifactoryModel.ArchiveResults results = stream == null? null : gson.fromJson(new InputStreamReader(stream,
                                                                                                                   StandardCharsets.UTF_8), ArtifactoryModel.ArchiveResults.class);
        if (results != null && results.results != null) {
          for (ArtifactoryModel.ArchiveResult result : results.results) {
            for (String uri : result.archiveUris) {
              if (!uri.endsWith(packaging)) continue;
              artifacts.add(convertArtifactInfo(uri, url, result.entry));
            }
          }
        }
      }
      return artifacts;
    }
    catch (JsonSyntaxException e) {
      return Collections.emptyList();
    }
    catch (Exception e) {
      throw new IOException(e);
    }
  }

  private static RepositoryArtifactDescription convertArtifactInfo(String uri, String baseUri, String className) throws IOException {
    final String repoPathFile = uri.substring((baseUri + "storage/").length());
    final int repoIndex = repoPathFile.indexOf('/');
    final String repoString = repoPathFile.substring(0, repoIndex);
    final String repo = repoString.endsWith("-cache") ? repoString.substring(0, repoString.lastIndexOf('-')) : repoString;
    final String filePath = repoPathFile.substring(repoIndex + 1, repoPathFile.lastIndexOf('/'));
    final int artIdIndex = filePath.lastIndexOf('/');
    final String version = filePath.substring(artIdIndex + 1);
    final String groupArtifact = filePath.substring(0, artIdIndex);
    final int groupIndex = groupArtifact.lastIndexOf('/');
    final String artifact = groupArtifact.substring(groupIndex + 1);
    final String group = groupArtifact.substring(0, groupIndex).replace('/', '.');
    final String packaging = uri.substring(uri.lastIndexOf('.') + 1);

    return new RepositoryArtifactDescription(group, artifact, version, packaging, null, className, repo);
  }
}
