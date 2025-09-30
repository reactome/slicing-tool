# slicing-tool

This is the project that is used to slice release content from the curation database (in MySQL or Neo4j). The original codebase is refactored from the CurationTool project.

**TODO**: 

- need to check replacementDB_IDs in _Deleted (Deleted) after conversting. This needs to create a new graph database. 
- Need stableIds at the node level in case the node is shell by needed in the context (e.g. replacementInstnstance in Deleted)
