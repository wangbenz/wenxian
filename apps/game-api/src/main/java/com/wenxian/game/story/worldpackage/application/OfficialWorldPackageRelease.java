package com.wenxian.game.story.worldpackage.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.zip.*;

/** 受控 classpath 发布制品入口；兼容打包 JAR，使用统一安全摄取与发布，不承担运行时读取。 */
@Service
public class OfficialWorldPackageRelease {
    private final WorldPackagePublicationService publications;
    private final ObjectMapper mapper;
    private final String root;
    public OfficialWorldPackageRelease(WorldPackagePublicationService publications, ObjectMapper mapper,
            @Value("${game.world-packages.release-root:world-packages/official/XUANXU/2/}") String root) {
        this.publications = publications; this.mapper = mapper; this.root = root;
    }
    public WorldPackageCandidate inspect() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath*:" + root + "**/*")) {
                    String uri = resource.getURL().toString();
                    if (uri.endsWith("/")) continue;
                    int offset = uri.lastIndexOf(root);
                    if (offset < 0) throw new IllegalStateException("发布制品路径不匹配");
                    String relative = uri.substring(offset + root.length());
                    zip.putNextEntry(new ZipEntry(relative));
                    try (var input = resource.getInputStream()) { input.transferTo(zip); }
                    zip.closeEntry();
                }
            }
            return new WorldPackageIngestionPipeline(mapper).inspectZip(new ByteArrayInputStream(bytes.toByteArray()));
        } catch (IOException e) { throw new IllegalStateException("官方发布制品无法读取", e); }
    }
    public RevisionRow publish() {
        var candidate = inspect(); var i = candidate.document().identity(); var system = Principal.systemPublisher();
        var p = publications.registerOfficial(system, i.publisherNamespace(), i.packageId());
        return publications.publish(p.id(), candidate, system, "official:" + i.revision() + ":" + candidate.contentHash(), true);
    }
}
