Note: This direction is a fundamental shift from the original intent of the mod, and shifts it from being a political map generator to a kingdom generator. 

(1) Configuration: Through the datapacks feature, allow the user to specify factions, their names, their associated structures, and all else as it currently exists in this mod. Additionally, one of those structures needs to be designated as a "Capital" structure for that faction. In a standing configuration setting file outside of datapacks, the player should be able to designate
-How many capitals (variable X) to generate in a world (Default = 3)
-How many blocks (variable Y) from the origin a capital is allowed to be (Default = 250) 
-How many blocks (variable Z) from another capital a capital is allowed to be (Default = 500)
-The minimum (variable A) and maximum (variable B) number of additional structures found within a realm (Defaults = 3 and 5)
-The minimum (variable C) and maximum (variable D) range from the capital a supporting structure is allowed to be (Defaults = 100 and 200)
-How many blocks (variable E) a supportins structure must be from another supporting structure (Default = 50)

(2) Capital Placement: Refer to the number of capitals to generate in the world and randomly select X number of unique factions. Within the playable world (see github.com/thanwiggins/worldborder, I mod that I use to restrict the world from limitless to something more manageable. Oftentimes I set my world border to +/- 1000 in both the x and z directions, and that usually feels about right), randomly choose X number of locations at least Y away form the origin. For each location X, ensure that it is at least Z away from all other set capitals, and is a valid structure location that meets ALL the criteria for generation. If not, re-roll and try again until there are X set capital locations in the playable world. Retry up to 10 times on one world seed, otherwise flush the world seed and start over. ONCE ALL CAPITAL LOCATIONS ARE FINALIZED, THE SELECTED CAPTIAL STRUCTURES WILL FORCE GENERATE AT THESE LOCATIONS - THERE ARE NO EXCEPTIONS. 

(3) Realm Building: Now that there are X capitals established in the world, other structures in that faction set should be found around the capital. For each capital, randomly generate a value between A and B. For each, randomly select a valid non-capital structure and randomly a location between C and D away from the capital in question. Ensure that it is at least E away from other supporting structures and is a valid structure location for the supporting structure that meets ALL the criteria for generation. If not, re-roll and try again until all supporting structure locations are set. Retry up to 5 times per structure before giving up. ONCE ALL SUPPORTING STRUCTURE LOCATIONS ARE FINALIZED, THE SELECTED STRUCTURES WILL FORCE GENERATE AT THESE LOCATIONS - THERE ARE NO EXCEPTIONS.

(4) Normal Structure Generation: After these special steps, structure generation happens as normal for the rest of the world.

Special Rules:
-Capital structures will not generate outside of the Capital placement routine. These are the only capital structures that will exist in the playable world.
-Supporting structures for factions that have capitals will not generate outside of the Realm Supporting Structure placement routine.

Mod Interactions:
Than's Worldbuilder: If this mod is installed, we'll use the world border set there. Otherwise, we'll default to +/- 1000 x/z as the world border. (https://github.com/thanwiggins/worldbuilder)
