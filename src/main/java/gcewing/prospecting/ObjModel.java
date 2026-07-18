package gcewing.prospecting;

import java.io.*;
import java.util.*;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ResourceLocation;

import gcewing.prospecting.BaseModClient.*;

public class ObjModel implements IModel {

    public double[] bounds;
    public Face[] faces;
    public double[][] boxes;

    public static class Face {
        int texture;
        double[][] vertices;
        int[][] triangles;
        Vector3 normal;
    }

    public static ObjModel fromResource(ResourceLocation location) {
        String path = String.format("/assets/%s/%s", location.getResourceDomain(), location.getResourcePath());
        InputStream in = ObjModel.class.getResourceAsStream(path);
        if (in == null)
            throw new RuntimeException(String.format("Cannot find resource %s", path));
        ObjModel model = parse(in);
        model.prepare();
        return model;
    }

    static ObjModel parse(InputStream in) {
        ObjModel model = new ObjModel();

        List<Double> vList = new ArrayList<>();
        List<Double> vtList = new ArrayList<>();
        List<Double> vnList = new ArrayList<>();

        List<Face> faceList = new ArrayList<>();
        List<double[]> boxList = new ArrayList<>();

        List<double[]> curVerts = new ArrayList<>();
        List<int[]> curTris = new ArrayList<>();
        int curTex = 0;
        int vertBase = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    // Parse metadata from comments
                    if (line.startsWith("# bounds:")) {
                        String[] parts = line.substring(9).trim().split("\\s+");
                        if (parts.length == 6) {
                            model.bounds = new double[]{
                                Double.parseDouble(parts[0]),
                                Double.parseDouble(parts[1]),
                                Double.parseDouble(parts[2]),
                                Double.parseDouble(parts[3]),
                                Double.parseDouble(parts[4]),
                                Double.parseDouble(parts[5])
                            };
                        }
                    } else if (line.startsWith("# box:")) {
                        String[] parts = line.substring(6).trim().split("\\s+");
                        if (parts.length == 6) {
                            boxList.add(new double[]{
                                Double.parseDouble(parts[0]),
                                Double.parseDouble(parts[1]),
                                Double.parseDouble(parts[2]),
                                Double.parseDouble(parts[3]),
                                Double.parseDouble(parts[4]),
                                Double.parseDouble(parts[5])
                            });
                        }
                    }
                    continue;
                }

                String[] parts = line.split("\\s+");
                if (parts.length == 0) continue;

                switch (parts[0]) {
                    case "v":
                        vList.add(Double.parseDouble(parts[1]));
                        vList.add(Double.parseDouble(parts[2]));
                        vList.add(Double.parseDouble(parts[3]));
                        break;
                    case "vt":
                        vtList.add(Double.parseDouble(parts[1]));
                        vtList.add(Double.parseDouble(parts[2]));
                        break;
                    case "vn":
                        vnList.add(Double.parseDouble(parts[1]));
                        vnList.add(Double.parseDouble(parts[2]));
                        vnList.add(Double.parseDouble(parts[3]));
                        break;
                    case "usemtl": {
                        // Flush current face group
                        if (!curVerts.isEmpty()) {
                            Face face = new Face();
                            face.texture = curTex;
                            face.vertices = curVerts.toArray(new double[0][]);
                            face.triangles = curTris.toArray(new int[0][]);
                            faceList.add(face);
                            curVerts = new ArrayList<>();
                            curTris = new ArrayList<>();
                        }
                        // Parse texture index from mtl name "texN"
                        String mtlName = parts[1];
                        if (mtlName.startsWith("tex")) {
                            curTex = Integer.parseInt(mtlName.substring(3));
                        }
                        vertBase = vList.size() / 3;
                        break;
                    }
                    case "f": {
                        int[] idx = new int[parts.length - 1];
                        for (int i = 1; i < parts.length; i++) {
                            // Parse v/vt/vn or v//vn or v
                            String[] indices = parts[i].split("/");
                            int vi = Integer.parseInt(indices[0]) - 1;
                            int vti = indices.length > 1 && !indices[1].isEmpty()
                                ? Integer.parseInt(indices[1]) - 1 : vi;
                            int vni = indices.length > 2 && !indices[2].isEmpty()
                                ? Integer.parseInt(indices[2]) - 1 : vi;

                            double x = vList.get(vi * 3);
                            double y = vList.get(vi * 3 + 1);
                            double z = vList.get(vi * 3 + 2);

                            double nx = 0, ny = 0, nz = 0;
                            if (vni >= 0 && vni < vnList.size() / 3) {
                                nx = vnList.get(vni * 3);
                                ny = vnList.get(vni * 3 + 1);
                                nz = vnList.get(vni * 3 + 2);
                            }

                            double u = 0, v = 0;
                            if (vti >= 0 && vti < vtList.size() / 2) {
                                u = vtList.get(vti * 2);
                                v = vtList.get(vti * 2 + 1);
                            }

                            curVerts.add(new double[]{x, y, z, nx, ny, nz, u, v});
                            idx[i - 1] = curVerts.size() - 1;
                        }
                        // Triangulate: fan triangulation for n-gons
                        for (int i = 1; i < idx.length - 1; i++) {
                            curTris.add(new int[]{idx[0], idx[i], idx[i + 1]});
                        }
                        break;
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error parsing OBJ model", e);
        }

        // Flush last face group
        if (!curVerts.isEmpty()) {
            Face face = new Face();
            face.texture = curTex;
            face.vertices = curVerts.toArray(new double[0][]);
            face.triangles = curTris.toArray(new int[0][]);
            faceList.add(face);
        }

        model.faces = faceList.toArray(new Face[0]);
        model.boxes = boxList.toArray(new double[0][]);

        if (model.bounds == null && !vList.isEmpty()) {
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (int i = 0; i < vList.size(); i += 3) {
                minX = Math.min(minX, vList.get(i));
                minY = Math.min(minY, vList.get(i + 1));
                minZ = Math.min(minZ, vList.get(i + 2));
                maxX = Math.max(maxX, vList.get(i));
                maxY = Math.max(maxY, vList.get(i + 1));
                maxZ = Math.max(maxZ, vList.get(i + 2));
            }
            model.bounds = new double[]{minX, minY, minZ, maxX, maxY, maxZ};
        }

        return model;
    }

    void prepare() {
        if (faces == null) return;
        for (Face face : faces) {
            if (face.triangles == null || face.triangles.length == 0) continue;
            double[][] p = face.vertices;
            int[] t = face.triangles[0];
            face.normal = Vector3.unit(
                Vector3.sub(p[t[1]], p[t[0]])
                    .cross(Vector3.sub(p[t[2]], p[t[0]])));
        }
    }

    public AxisAlignedBB getBounds() {
        return AxisAlignedBB.getBoundingBox(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
    }

    public void addBoxesToList(Trans3 t, List list) {
        if (boxes != null && boxes.length > 0) {
            for (int i = 0; i < boxes.length; i++)
                addBoxToList(boxes[i], t, list);
        } else {
            addBoxToList(bounds, t, list);
        }
    }

    protected void addBoxToList(double[] b, Trans3 t, List list) {
        t.addBox(b[0], b[1], b[2], b[3], b[4], b[5], list);
    }

    public void render(Trans3 t, IRenderTarget renderer, ITexture... textures) {
        Vector3 p = null, n = null;
        for (Face face : faces) {
            int k = face.texture;
            if (k >= textures.length) k = textures.length - 1;
            ITexture tex = textures[k];
            if (tex != null) {
                renderer.setTexture(tex);
                for (int[] tri : face.triangles) {
                    renderer.beginTriangle();
                    for (int i = 0; i < 3; i++) {
                        int j = tri[i];
                        double[] c = face.vertices[j];
                        p = t.p(c[0], c[1], c[2]);
                        n = t.v(c[3], c[4], c[5]);
                        renderer.setNormal(n);
                        renderer.addVertex(p, c[6], c[7]);
                    }
                    renderer.endFace();
                }
            }
        }
    }

}
