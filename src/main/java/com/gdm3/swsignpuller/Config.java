package com.gdm3.swsignpuller;
// default config class, unchanged
import java.util.List;
import java.util.Objects; // Import Objects for null check
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
// It's good practice to import your main mod class if you reference its logger or constants
// import static com.example.examplemod.SignPuller.LOGGER;
// import static com.example.examplemod.SignPuller.MODID;


@EventBusSubscriber(modid = SignPuller.MODID, bus = EventBusSubscriber.Bus.MOD)
public class Config
{
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("logDirtBlock", true);

    private static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("magicNumberIntroduction", "The magic number is... ");

    private static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("items", List.of("minecraft:stone"), Config::validateItemName);

    static final ModConfigSpec SPEC = BUILDER.build();

    // Public fields to hold the loaded config values
    public static boolean logDirtBlock;
    public static int magicNumber;
    public static String magicNumberIntroduction;
    public static Set<Item> items;

    private static boolean validateItemName(final Object obj)
    {
        if (obj instanceof final String itemName) {
            ResourceLocation resourcelocation = ResourceLocation.tryParse(itemName);
            // containsKey is correct for checking existence
            return resourcelocation != null && BuiltInRegistries.ITEM.containsKey(resourcelocation);
        }
        return false;
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent.Loading event)
    {
        if (event.getConfig().getSpec() == Config.SPEC) {
            loadConfig();
        }
    }

    @SubscribeEvent
    static void onReload(final ModConfigEvent.Reloading event)
    {
        if (event.getConfig().getSpec() == Config.SPEC) {
            loadConfig();
        }
    }

    private static void loadConfig() {
        logDirtBlock = LOG_DIRT_BLOCK.get();
        magicNumber = MAGIC_NUMBER.get();
        magicNumberIntroduction = MAGIC_NUMBER_INTRODUCTION.get();

        items = ITEM_STRINGS.get().stream()
                .map(itemName -> ResourceLocation.tryParse(itemName)) // Safely parse
                .filter(Objects::nonNull) // Filter out invalid syntax results
                // *** Use .get(ResourceLocation) instead of .getValue() ***
                .map(loc -> BuiltInRegistries.ITEM.get(loc))
                .filter(Objects::nonNull) // Filter out items not found (though validation should prevent this)
                .collect(Collectors.toSet());

        // Optional logging (Uncomment if you have LOGGER available)
        // LOGGER.info("Loaded/Reloaded {} config", MODID);
    }
}