package be.elevenways.sketerm.api;

import be.elevenways.sketerm.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One web_read answer: reader-mode markdown plus the entities that can be acted on.
 *
 * @param readerIds false when an older helper answered markdown-only, in which case the entity
 *                  list is empty and a {@link Page#snapshot()} is needed before acting
 */
public record Article(String url,
                      String title,
                      boolean readerIds,
                      int document,
                      int revision,
                      String markdown,
                      List<Entity> entities) {

    /**
     * One addressable piece of the article, guarded to the read's document and revision.
     *
     * @param kind the entity kind the reader assigned, for example heading, link or item
     * @param url the link target, empty for entities that are not links
     */
    public record Entity(int id, String kind, String text, String url) {
    }

    static Article decode(Map<String, Object> structured) {

        List<Entity> entities = new ArrayList<>();
        List<Object> raw = Json.optList(structured, "entities");

        Long document = Json.optLong(structured, "document");
        Long revision = Json.optLong(structured, "revision");

        if (raw != null) {
            for (Object element : raw) {
                Map<String, Object> entity = Json.asMap(element, "a web_read entity");
                Long id = Json.optLong(entity, "id");

                entities.add(new Entity(id == null ? 0 : id.intValue(),
                        Json.optStr(entity, "kind"),
                        Json.optStr(entity, "text"),
                        Json.optStr(entity, "url")));
            }
        }

        return new Article(Json.optStr(structured, "url"),
                Json.optStr(structured, "title"),
                Json.optBool(structured, "reader_ids", false),
                document == null ? 0 : document.intValue(),
                revision == null ? 0 : revision.intValue(),
                Json.optStr(structured, "markdown"),
                List.copyOf(entities));
    }

    /**
     * @return a ref for an entity, guarded to this read's document and revision
     */
    public Ref refTo(Entity entity) {
        return new Ref(entity.id(), this.document, this.revision);
    }

    /**
     * @return the first entity whose text contains the argument, ignoring case
     */
    public Optional<Entity> findEntity(String text) {

        for (Entity entity : this.entities) {
            if (TreeText.containsIgnoreCase(entity.text(), text)) {
                return Optional.of(entity);
            }
        }

        return Optional.empty();
    }
}
