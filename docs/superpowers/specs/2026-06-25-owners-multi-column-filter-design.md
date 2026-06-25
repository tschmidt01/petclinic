# Owners List — Multi-Column Case-Insensitive Filter

**Date:** 2026-06-25
**Status:** Approved (design)

## Problem

The `/owners` list is filtered only by **last name**, using a case-sensitive
prefix match (`OwnerRepository.findByLastNameStartingWith`). Users cannot search
by other visible columns, and matching is case-sensitive.

## Goal

Extend the owners filter to a single search box that matches, **case-insensitively**,
as a **substring (contains)**, against **all** owner columns:

- first name
- last name
- address
- city
- telephone
- pets (by pet name)

An owner appears in the results if **any** of these fields contains the search term.
An empty search term returns all owners (unchanged behavior).

## Decisions

| Question | Decision |
|----------|----------|
| Filter UX | Single global search box (one input, matches any column) |
| Match rule | Contains (substring), case-insensitive |
| Filter location | Backend (extend JPA query) |
| API param | Rename `?lastName=` → `?search=` (old param removed) |
| Search trigger | Keep existing "Find Owner" button (search on click) |

## Backend Changes

### Repository

`OwnerRepository` — replace `findByLastNameStartingWith(String)` with:

```java
@Query("""
    SELECT DISTINCT o FROM Owner o LEFT JOIN o.pets p WHERE
      LOWER(o.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR
      LOWER(o.lastName)  LIKE LOWER(CONCAT('%', :search, '%')) OR
      LOWER(o.address)   LIKE LOWER(CONCAT('%', :search, '%')) OR
      LOWER(o.city)      LIKE LOWER(CONCAT('%', :search, '%')) OR
      LOWER(o.telephone) LIKE LOWER(CONCAT('%', :search, '%')) OR
      LOWER(p.name)      LIKE LOWER(CONCAT('%', :search, '%'))
    """)
List<Owner> searchOwners(@Param("search") String search);
```

Notes:
- `LEFT JOIN o.pets p` so owners **with no pets** still match on their own fields.
- `DISTINCT` dedupes owners that have multiple matching pets.
- Empty term `""` produces `'%%'`, which matches every owner → returns all.

### Controller

`OwnerRestController.listOwners`:
- Rename request param `lastName` → `search` (`@RequestParam(name = "search", defaultValue = "")`).
- Call `ownerRepository.searchOwners(search)`.
- Mapper unchanged.

### Generated artifacts

- Regenerate `openapi.yaml` via `OpenApiExtractorTest` (param rename propagates).

## Frontend Changes

### Service

`owner.service.ts`:
- `searchOwners(lastName)` → `searchOwners(search)`, query string `?search=` + term.

### Component

`owner-list.component.ts`:
- Rename field `lastName` → `search`.
- Rename `searchByLastName(...)` → `search(...)`; keep the empty/non-empty branching
  (empty → `getOwners()`, non-empty → `searchOwners(search)`).
- Remove unused `listOfOwnersWithLastName`.

### Template

`owner-list.component.html`:
- Label `Last name` → `Search`.
- Input bound to `search`; button still calls the search method on click.
- Update the "No owners with LastName starting with …" empty-state message to reflect
  global search (e.g. `No owners match "{{search}}"`).

## Testing (TDD)

Backend `OwnerTest` — write tests first, then implement:

1. Update existing `?lastName=` case to `?search=`.
2. Match by **city** substring.
3. Match by **telephone** substring.
4. Match by **pet name**.
5. Match by **first name**.
6. **Case-insensitive**: lower-case term matches mixed-case data.
7. **Substring** (not just prefix): mid-word term matches.
8. **Empty** `?search=` returns all owners.

Run via `mvn test`. Frontend: existing component/service specs updated for the rename.

## Out of Scope

- Per-column filter inputs.
- Live as-you-type search / debounce.
- Pagination or relevance ranking.
- Backward-compat for the old `?lastName=` param.
