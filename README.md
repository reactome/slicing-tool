# slicing-tool

This is the project that is used to slice release content from the curation database (in MySQL or Neo4j). The original codebase is refactored from the CurationTool project.

**Note**

The slicing tool runs directly using the controller in the curator-tool-ws. To run it in the development mode inside IDE (e.g. IntelliJ or Eclipse), 
you need to set up the run configuration for the curator-tool-ws module, and add the following enviornment variables in the running configuration or 
set up the enviornment variables in the terminal before running the application (org.gk.slicing.SlicingEngine.main()). For more details see: https://github.com/reactome/curator-tool-ws/blob/master/docs/QUICK_START_SECRETS.md.

# Neo4j Configuration - LOCAL DEVELOPMENT ONLY
NEO4J_URI=bolt://localhost:7687
NEO4J_USER={}
NEO4J_PASSWORD={}

# H2 Database Configuration - LOCAL DEVELOPMENT ONLY
DATASOURCE_URL=jdbc:h2:file:{};DB_CLOSE_DELAY=-1;FILE_LOCK=NO
DATASOURCE_USER={}
DATASOURCE_PASSWORD={}

Note: You may use .env file in IntelliJ. Also make sure the two h2 files in the data folder match with your local configuration. 

```

**TODO**: 

- need to check replacementDB_IDs in _Deleted (Deleted) after converting. This needs to create a new graph database. 
- Need stableIds at the node level in case the node is shell in the context (e.g. replacementInstnstance in Deleted)


