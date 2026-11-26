package dev.venatorvictor.equinox.client.clouds;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.venatorvictor.equinox.Equinox;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL43C.*;

/**
 * The clouds' cubes, made on the graphics card (compute shaders, OpenGL 4.3; without it the game keeps vanilla's
 * clouds). The sky is cut into chunks of 32 x 32 cubes fixed in wind space; near the camera the cubes are small, then
 * twice, then four times as big (levels of detail, a quadtree, so levels never overlap). A chunk is made in two passes
 * (which cubes are cloud; then their faces with the light baked in) into a scratch buffer, and copied into its own
 * buffer once the graphics card is done (a fence: nothing waits). Chunks are remade a few per frame in turn, so the
 * clouds change a cube at a time as the sky changes. Drawn in the game's cloud format and render types.
 */
public final class CloudGpu {
    /** Cubes per chunk side, grid side with the border, faces a chunk can have, bytes per vertex. */
    private static final int CHUNK = 32, GRID = CHUNK + 2, MAX_FACES = 1 << 16, VERTEX = 28;
    /** Chunks being made at once, domes a chunk can see, blocks around a chunk whose domes it sees. */
    private static final int SLOTS = 4, MAX_LIST = 2048, MARGIN = 96;
    /** A chunk isn't remade sooner than this after its last make (nanoseconds), so cubes finish growing first. */
    private static long minAge = 400_000_000L;
    public static final int PALETTE_W = 64, PALETTE_H = 32;

    private static Boolean supported;
    private static int fill, faces, domeBuffer, map0, map1;
    private static final Map<String, Integer> UNIFORMS = new HashMap<>();
    private static final Slot[] SLOT = new Slot[SLOTS];
    private static final Long2ObjectOpenHashMap<Chunk> CHUNKS = new Long2ObjectOpenHashMap<>();
    private static CloudSky.Sky sky;
    private static boolean warnedFull;

    /** A chunk: level, position (chunk units), cube size, its buffers, faces, when it was made. */
    static final class Chunk {
        final long key;
        final int level, cx, cz, cube;
        int vbo, vao, old, faces = -1;
        boolean hasOld;
        long made;
        boolean wanted, building;

        Chunk(long key, int level, int cx, int cz, int cube) {
            this.key = key;
            this.level = level;
            this.cx = cx;
            this.cz = cz;
            this.cube = cube;
        }

        int span() {
            return cube * CHUNK;
        }

        long x0() {
            return (long) cx * span();
        }

        long z0() {
            return (long) cz * span();
        }

        boolean ready() {
            return faces >= 0;
        }

        boolean overlaps(Chunk o) {
            return x0() < o.x0() + o.span() && o.x0() < x0() + span() && z0() < o.z0() + o.span() && o.z0() < z0() + span();
        }
    }

    private static final class Slot {
        int list, grid, out;
        long fence;
        Chunk target;
    }

    // ---- set-up

    /** Compute shaders work here (OpenGL 4.3); checked once, on the render thread. */
    public static boolean supported() {
        if (supported == null) {
            supported = false;
            try {
                GLCapabilities caps = GL.getCapabilities();
                if (!caps.OpenGL43) {
                    Equinox.LOGGER.info("Equinox: no OpenGL 4.3 here, so vanilla clouds stay");
                } else {
                    String common = read("common.glsl");
                    fill = program(common + read("fill.comp"));
                    faces = program(common + read("faces.comp"));
                    domeBuffer = glGenBuffers();
                    for (int i = 0; i < SLOTS; i++) {
                        Slot s = new Slot();
                        s.list = buffer(4L * (MAX_LIST + 1));
                        s.grid = buffer(4L * GRID * GRID * (256 / 4 + 2));
                        s.out = buffer(16L + (long) MAX_FACES * 4 * VERTEX);
                        SLOT[i] = s;
                    }
                    map0 = texture();
                    map1 = texture();
                    supported = true;
                }
            } catch (Exception e) {
                Equinox.LOGGER.warn("Equinox: the cloud shaders didn't work here, so vanilla clouds stay", e);
            }
        }
        return supported;
    }

    static String read(String name) throws IOException {
        try (InputStream in = CloudGpu.class.getResourceAsStream("/assets/" + Equinox.MOD_ID + "/clouds_gl/" + name)) {
            if (in == null) throw new IOException("missing " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int program(String source) {
        int shader = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(shader, "#version 430 core\n" + source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) throw new IllegalStateException(glGetShaderInfoLog(shader));
        int program = glCreateProgram();
        glAttachShader(program, shader);
        glLinkProgram(program);
        glDeleteShader(shader);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(program));
        int previous = glGetInteger(GL_CURRENT_PROGRAM);
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "uMap0"), 0);
        glUniform1i(glGetUniformLocation(program, "uMap1"), 1);
        glUseProgram(previous);
        return program;
    }

    private static int buffer(long bytes) {
        int id = glGenBuffers();
        glBindBuffer(GL_COPY_WRITE_BUFFER, id);
        glBufferData(GL_COPY_WRITE_BUFFER, bytes, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
        return id;
    }

    /** A float texture, filtered smoothly, clamped at the edge. */
    static int texture() {
        int id = GlStateManager._genTexture();
        GlStateManager._bindTexture(id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        GlStateManager._bindTexture(0);
        return id;
    }

    /** Fills a float texture (channels 1 or 4) of size x size. */
    static void upload(int id, float[] data, int size, int channels) {
        FloatBuffer buffer = MemoryUtil.memAllocFloat(data.length);
        try {
            buffer.put(data).flip();
            GlStateManager._bindTexture(id);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            if (channels == 4) glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, size, size, 0, GL_RGBA, GL_FLOAT, buffer);
            else glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, size, size, 0, GL_RED, GL_FLOAT, buffer);
            GlStateManager._bindTexture(0);
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    private static int uniform(int program, String name) {
        return UNIFORMS.computeIfAbsent(program + ":" + name, k -> glGetUniformLocation(program, name));
    }

    // ---- the sky

    /** A newly built sky: its domes and maps go to the graphics card; chunks pick it up as they are remade. */
    public static void setSky(CloudSky.Sky s) {
        sky = s;
        FloatBuffer domes = MemoryUtil.memAllocFloat(Math.max(12, s.domeCount() * CloudSky.DOME_FLOATS));
        try {
            domes.put(s.domes(), 0, s.domeCount() * CloudSky.DOME_FLOATS);
            while (domes.position() < 12) domes.put(0);
            domes.flip();
            glBindBuffer(GL_COPY_WRITE_BUFFER, domeBuffer);
            glBufferData(GL_COPY_WRITE_BUFFER, domes, GL_DYNAMIC_DRAW);
            glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
        } finally {
            MemoryUtil.memFree(domes);
        }
        upload(map0, s.map0(), s.mapSize(), 4);
        upload(map1, s.map1(), s.mapSize(), 4);
    }

    public static CloudSky.Sky sky() {
        return sky;
    }

    // ---- which chunks

    /** Level sizes: near cube size, then doubled, at most 16-block cubes. */
    private static int[] cubes(int near) {
        List<Integer> list = new ArrayList<>();
        for (int c = near; c <= 16; c *= 2) list.add(c);
        if (list.isEmpty()) list.add(16);
        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    private static long key(int level, int cx, int cz) {
        return ((long) level << 60) ^ (((long) cx & 0x3FFFFFFFL) << 30) ^ ((long) cz & 0x3FFFFFFFL);
    }

    /** Distance (blocks) from a point to a chunk's square. */
    private static double distance(double x, double z, long x0, long z0, int span) {
        double dx = Math.max(Math.max(x0 - x, x - (x0 + span)), 0), dz = Math.max(Math.max(z0 - z, z - (z0 + span)), 0);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Marks the chunks wanted around the camera (wind space): a quadtree, split while nearer than the ring. */
    private static void want(double camX, double camZ, int reach, int nearCube, int ring) {
        for (Chunk c : CHUNKS.values()) c.wanted = false;
        int[] sizes = cubes(nearCube);
        int top = sizes.length - 1, span = sizes[top] * CHUNK;
        int x0 = (int) Math.floor((camX - reach) / span), x1 = (int) Math.floor((camX + reach) / span);
        int z0 = (int) Math.floor((camZ - reach) / span), z1 = (int) Math.floor((camZ + reach) / span);
        for (int cx = x0; cx <= x1; cx++)
            for (int cz = z0; cz <= z1; cz++) want(top, cx, cz, sizes, camX, camZ, reach, ring);
    }

    private static void want(int level, int cx, int cz, int[] sizes, double camX, double camZ, int reach, int ring) {
        int span = sizes[level] * CHUNK;
        long x0 = (long) cx * span, z0 = (long) cz * span;
        double d = distance(camX, camZ, x0, z0, span);
        if (d > reach) return;
        // level L covers out to ring * 3^L blocks
        if (level > 0 && d < ring * Math.pow(3, level - 1)) {
            for (int i = 0; i < 2; i++)
                for (int j = 0; j < 2; j++) want(level - 1, cx * 2 + i, cz * 2 + j, sizes, camX, camZ, reach, ring);
            return;
        }
        long key = key(level, cx, cz);
        Chunk c = CHUNKS.get(key);
        if (c == null) {
            c = new Chunk(key, level, cx, cz, sizes[level]);
            CHUNKS.put(key, c);
        }
        c.wanted = true;
    }

    // ---- making chunks

    /**
     * Once a frame: collects finished chunks, drops chunks no longer needed (once what replaces them is ready) and
     * starts making up to `budget` chunks: missing ones nearest first, then the oldest. camX, camZ: wind space.
     */
    public static void update(double camX, double camZ, int reach, int nearCube, int ring, int budget, float[] sun, boolean full,
                              int stamp, float animSeconds) {
        if (sky == null) return;
        minAge = (long) ((Math.max(0.4F, animSeconds + 0.2F)) * 1e9);
        collect();
        want(camX, camZ, reach, nearCube, ring);
        // drop unwanted chunks once every wanted chunk over them is ready
        List<Chunk> wanted = new ArrayList<>(), drop = new ArrayList<>();
        for (Chunk c : CHUNKS.values()) if (c.wanted) wanted.add(c);
        for (Chunk c : CHUNKS.values()) {
            if (c.wanted) continue;
            boolean covered = true;
            if (c.ready() && distance(camX, camZ, c.x0(), c.z0(), c.span()) <= reach)
                for (Chunk w : wanted) if (!w.ready() && w.overlaps(c)) { covered = false; break; }
            if (covered) drop.add(c);
        }
        for (Chunk c : drop) free(c);

        long now = System.nanoTime();
        for (int n = 0; n < budget; n++) {
            Slot slot = freeSlot();
            if (slot == null) break;
            Chunk best = null;
            double bestScore = Double.MAX_VALUE;
            for (Chunk c : wanted) {
                if (c.building) continue;
                double score;
                if (!c.ready()) score = distance(camX, camZ, c.x0(), c.z0(), c.span()) - 1e9;
                else if (now - c.made < minAge) continue;
                else score = c.made - now;
                if (score < bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
            if (best == null) break;
            build(slot, best, sun, full, stamp, animSeconds > 0);
        }
    }

    private static Slot freeSlot() {
        for (Slot s : SLOT) if (s.fence == 0) return s;
        return null;
    }

    /** Starts making a chunk in a slot (two compute passes, then a fence). */
    private static void build(Slot slot, Chunk chunk, float[] sun, boolean full, int stamp, boolean animate) {
        CloudSky.Sky s = sky;
        int cube = chunk.cube, layers = (int) (CloudSky.TOP - CloudSky.BOTTOM) / cube, span = chunk.span();
        float relX = (float) (chunk.x0() - s.centerX()), relZ = (float) (chunk.z0() - s.centerZ());
        int[] saved = saveBindings();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // the domes this chunk can see
            IntBuffer list = MemoryUtil.memAllocInt(MAX_LIST + 1);
            try {
                list.put(0);
                float[] d = s.domes();
                int count = 0;
                for (int k = 0; k < s.domeCount() && count < MAX_LIST; k++) {
                    int o = k * CloudSky.DOME_FLOATS;
                    float r = Math.max(d[o + 4], d[o + 5]) + 16 + MARGIN;
                    if (d[o] + r < relX || d[o] - r > relX + span || d[o + 1] + r < relZ || d[o + 1] - r > relZ + span) continue;
                    list.put(k);
                    count++;
                }
                list.put(0, count);
                list.flip();
                glBindBuffer(GL_COPY_WRITE_BUFFER, slot.list);
                glBufferSubData(GL_COPY_WRITE_BUFFER, 0, list);
            } finally {
                MemoryUtil.memFree(list);
            }
            glBindBuffer(GL_COPY_WRITE_BUFFER, slot.out);
            glBufferSubData(GL_COPY_WRITE_BUFFER, 0, stack.callocInt(4));
            glBindBuffer(GL_COPY_WRITE_BUFFER, 0);

            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, domeBuffer);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, slot.list);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, slot.grid);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, slot.out);
            boolean hasOld = animate && chunk.hasOld;
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, hasOld ? chunk.old : slot.grid);
            GlStateManager._activeTexture(GL_TEXTURE0 + 1);
            GlStateManager._bindTexture(map1);
            GlStateManager._activeTexture(GL_TEXTURE0);
            GlStateManager._bindTexture(map0);

            int previous = glGetInteger(GL_CURRENT_PROGRAM);
            for (int pass = 0; pass < 2; pass++) {
                int program = pass == 0 ? fill : faces;
                glUseProgram(program);
                glUniform2i(uniform(program, "uCentre"), s.centerX(), s.centerZ());
                glUniform2f(uniform(program, "uMapOrigin"), s.mapX0(), s.mapZ0());
                glUniform1f(uniform(program, "uMapSpan"), (float) s.mapSize() * CloudSky.MAP_CELL);
                double a = Math.atan2(s.windZ(), s.windX()) / (Math.PI * 2) * 16;
                int ai = (int) Math.floor(a);
                glUniform1i(uniform(program, "uAngle"), Math.floorMod(ai, 16));
                glUniform1f(uniform(program, "uAngleF"), (float) (a - ai));
                glUniform1i(uniform(program, "uCube"), cube);
                glUniform3f(uniform(program, "uChunk"), relX, 0, relZ);
                glUniform3i(uniform(program, "uChunkCell"), chunk.cx * CHUNK, Math.floorDiv((int) CloudSky.BOTTOM, cube), chunk.cz * CHUNK);
                glUniform1i(uniform(program, "uLayers"), layers);
                glUniform1f(uniform(program, "uBottom"), CloudSky.BOTTOM);
                if (pass == 0) {
                    glDispatchCompute((GRID + 3) / 4, (layers + 2 + 3) / 4, (GRID + 3) / 4);
                    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
                } else {
                    glUniform3f(uniform(program, "uSun"), sun[0], sun[1], sun[2]);
                    glUniform1i(uniform(program, "uLight"), full ? 1 : 0);
                    glUniform1ui(uniform(program, "uMaxFaces"), MAX_FACES);
                    glUniform2f(uniform(program, "uPalette"), PALETTE_W, PALETTE_H);
                    glUniform1i(uniform(program, "uHasOld"), hasOld ? 1 : 0);
                    glUniform1ui(uniform(program, "uStamp"), stamp & 1023);
                    glDispatchCompute(CHUNK / 4, (layers + 3) / 4, CHUNK / 4);
                    glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
                }
            }
            glUseProgram(previous);
            // keep this make's cubes, to see next time which came and went
            long gridBytes = 4L * GRID * GRID * (layers + 2);
            glMemoryBarrier(GL_ALL_BARRIER_BITS);
            if (chunk.old == 0) {
                chunk.old = glGenBuffers();
                glBindBuffer(GL_COPY_WRITE_BUFFER, chunk.old);
                glBufferData(GL_COPY_WRITE_BUFFER, gridBytes, GL_DYNAMIC_COPY);
            }
            glBindBuffer(GL_COPY_READ_BUFFER, slot.grid);
            glBindBuffer(GL_COPY_WRITE_BUFFER, chunk.old);
            glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0, 0, gridBytes);
            glBindBuffer(GL_COPY_READ_BUFFER, 0);
            glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
            chunk.hasOld = true;
        } finally {
            restoreBindings(saved);
        }
        slot.fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        slot.target = chunk;
        chunk.building = true;
    }

    /** Shader storage bindings 0-4 as they were (shader packs may use them). */
    private static int[] saveBindings() {
        int[] saved = new int[5];
        for (int i = 0; i < 5; i++) saved[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        return saved;
    }

    private static void restoreBindings(int[] saved) {
        for (int i = 0; i < 5; i++) glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, saved[i]);
    }

    /** Finished chunks: their faces copied from the slot into the chunk's own buffer. */
    private static void collect() {
        for (Slot slot : SLOT) {
            if (slot.fence == 0) continue;
            int status = glClientWaitSync(slot.fence, 0, 0);
            if (status != GL_ALREADY_SIGNALED && status != GL_CONDITION_SATISFIED) continue;
            glDeleteSync(slot.fence);
            slot.fence = 0;
            Chunk chunk = slot.target;
            slot.target = null;
            if (chunk == null) continue;
            chunk.building = false;
            int count;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer n = stack.mallocInt(1);
                glBindBuffer(GL_COPY_READ_BUFFER, slot.out);
                glGetBufferSubData(GL_COPY_READ_BUFFER, 0, n);
                count = n.get(0);
            }
            if (count > MAX_FACES && !warnedFull) {
                warnedFull = true;
                Equinox.LOGGER.warn("Equinox: a cloud chunk had more faces than fit ({}), some are left out", count);
            }
            count = Math.min(count, MAX_FACES);
            if (count > 0) {
                if (chunk.vbo == 0) makeArrays(chunk);
                long bytes = (long) count * 4 * VERTEX;
                glBindBuffer(GL_COPY_WRITE_BUFFER, chunk.vbo);
                glBufferData(GL_COPY_WRITE_BUFFER, bytes, GL_STATIC_DRAW);
                glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 16, 0, bytes);
                glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
            }
            glBindBuffer(GL_COPY_READ_BUFFER, 0);
            chunk.faces = count;
            chunk.made = System.nanoTime();
        }
    }

    /** The chunk's vertex buffer and its vertex array (the game's cloud vertex format). */
    private static void makeArrays(Chunk chunk) {
        chunk.vbo = glGenBuffers();
        chunk.vao = glGenVertexArrays();
        BufferUploader.invalidate();
        GlStateManager._glBindVertexArray(chunk.vao);
        GlStateManager._glBindBuffer(GL_ARRAY_BUFFER, chunk.vbo);
        glBufferData(GL_ARRAY_BUFFER, VERTEX * 4, GL_STATIC_DRAW);
        DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL.setupBufferState();
        GlStateManager._glBindVertexArray(0);
        GlStateManager._glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    private static void free(Chunk c) {
        CHUNKS.remove(c.key);
        for (Slot s : SLOT) if (s.target == c) s.target = null;
        if (c.vbo != 0) glDeleteBuffers(c.vbo);
        if (c.vao != 0) glDeleteVertexArrays(c.vao);
        if (c.old != 0) glDeleteBuffers(c.old);
        c.vbo = c.vao = c.old = 0;
    }

    // ---- drawing

    /** Something to know per chunk while drawing: its corner relative to the camera (blocks). */
    public interface Tint {
        /** Sets the shader colour and uniforms for a chunk whose middle is at this camera-relative position. */
        void apply(float x, float y, float z, int cube);
    }

    /**
     * Draws the ready chunks with the current shader (the render type is set up). offX, offZ: the wind's offset now;
     * height: the cloud height.
     */
    public static void draw(Matrix4f view, Matrix4f projection, double camX, double camY, double camZ, double offX, double offZ,
                            float height, ShaderInstance shader, Tint tint) {
        FrustumIntersection frustum = new FrustumIntersection(new Matrix4f(projection).mul(view));
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        float y0 = (float) (height + CloudSky.BOTTOM - camY), y1 = (float) (height + CloudSky.TOP - camY);
        for (Chunk c : CHUNKS.values()) {
            if (!c.ready() || c.faces <= 0) continue;
            float x = (float) (c.x0() + offX - camX), z = (float) (c.z0() + offZ - camZ);
            if (!frustum.testAab(x, y0, z, x + c.span(), y1, z + c.span())) continue;
            tint.apply(x + c.span() * 0.5F, (y0 + y1) * 0.5F, z + c.span() * 0.5F, c.cube);
            Matrix4f pose = new Matrix4f(view).translate(x, (float) (height - camY), z);
            shader.setDefaultUniforms(VertexFormat.Mode.QUADS, pose, projection, Minecraft.getInstance().getWindow());
            shader.apply();
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(c.vao);
            indices.bind(c.faces * 6);
            RenderSystem.drawElements(VertexFormat.Mode.QUADS.asGLMode, c.faces * 6, indices.type().asGLType);
            shader.clear();
        }
        VertexBuffer.unbind();
    }

    /** Everything dropped (leaving a world, settings changed). */
    public static void clear() {
        if (supported == null || !supported) return;
        for (Chunk c : new ArrayList<>(CHUNKS.values())) free(c);
        for (Slot s : SLOT) {
            if (s.fence != 0) glDeleteSync(s.fence);
            s.fence = 0;
            s.target = null;
        }
        sky = null;
    }

    /** Chunks shown and faces drawn (for the debug screen). */
    public static int[] stats() {
        int n = 0, f = 0;
        for (Chunk c : CHUNKS.values()) if (c.ready()) {
            n++;
            f += Math.max(0, c.faces);
        }
        return new int[] {n, f};
    }

    private CloudGpu() {
    }
}
